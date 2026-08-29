#!/usr/bin/env bash
#
# 배포 서버에서 새 이미지를 반영합니다.
#
# 백엔드를 한 번에 둘 다 바꾸지 않습니다. A 를 먼저 바꾸고 health 가 통과한 뒤에 B 를
# 바꿉니다. 그래야 새 이미지가 뜨지 못하는 경우에 B 가 계속 요청을 받습니다.
#
# Flyway 는 애플리케이션 기동 시점에 돕니다. 그래서 마이그레이션은 A 가 적용하고, B 는
# 이미 적용된 스키마 위에서 뜹니다. A 가 실패하면 B 는 건드리지 않습니다.
#
# 사용:
#   deploy.sh --environment production|staging --backend-image <ref> [--frontend-image <ref>]
#
# ref 는 digest(@sha256:...) 또는 commit SHA 태그를 씁니다. latest 나 main 같은 이동
# 태그를 쓰면 무엇이 배포됐는지 나중에 지목할 수 없고 rollback 대상도 정해지지 않습니다.

set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-/srv/mopl/app/docker-compose.prod.yml}"
MOPL_CONFIG_DIR="${MOPL_CONFIG_DIR:-/etc/mopl}"
ENV_FILE="${ENV_FILE:-}"
STATE_FILE="${STATE_FILE:-${MOPL_CONFIG_DIR}/deploy-state.env}"

# health 가 통과할 때까지 기다리는 한도입니다. 백엔드는 start_period 가 90s 이고 Kafka·
# Elasticsearch 연결까지 붙어야 readiness 가 올라옵니다.
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
HEALTH_INTERVAL="${HEALTH_INTERVAL:-5}"

BACKEND_IMAGE=""
FRONTEND_IMAGE=""
DEPLOY_COMMIT="${DEPLOY_COMMIT:-unknown}"
DEPLOY_ENVIRONMENT="${DEPLOY_ENVIRONMENT:-production}"

STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
fail() { printf '\n\033[31m!! %s\033[0m\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case $1 in
        --backend-image)  BACKEND_IMAGE=$2; shift 2 ;;
        --frontend-image) FRONTEND_IMAGE=$2; shift 2 ;;
        --commit)         DEPLOY_COMMIT=$2; shift 2 ;;
        --environment)    DEPLOY_ENVIRONMENT=$2; shift 2 ;;
        *) fail "알 수 없는 인자: $1" ;;
    esac
done

case ${DEPLOY_ENVIRONMENT} in
    production) DEFAULT_ENV_FILE="${MOPL_CONFIG_DIR}/prod.env" ;;
    staging)    DEFAULT_ENV_FILE="${MOPL_CONFIG_DIR}/staging.env" ;;
    *) fail "지원하지 않는 환경: ${DEPLOY_ENVIRONMENT}
   --environment 는 production 또는 staging 이어야 합니다." ;;
esac

ENV_FILE="${ENV_FILE:-${DEFAULT_ENV_FILE}}"

[[ -n ${BACKEND_IMAGE} ]] || fail "--backend-image 가 필요합니다."
[[ -r ${ENV_FILE} ]]      || fail "${ENV_FILE} 를 읽을 수 없습니다."
[[ -r ${COMPOSE_FILE} ]]  || fail "${COMPOSE_FILE} 를 읽을 수 없습니다."

case ${BACKEND_IMAGE} in
    *:main|*:develop|*:latest)
        fail "이동 태그(${BACKEND_IMAGE##*:})는 배포 대상이 될 수 없습니다.
   digest 또는 commit SHA 태그를 쓰세요. 이동 태그는 나중에 다른 이미지를 가리킵니다." ;;
esac

compose() { docker compose --file "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"; }

# ECR 로그인 토큰은 12시간 뒤 만료됩니다. 서버 준비 때 한 번 로그인한 상태에 기대면,
# 애플리케이션과 무관하게 다음 배포의 pull 이 실패합니다. EC2 인스턴스 역할로 매 배포
# 직전에 단기 토큰을 다시 받아 deploy 사용자의 Docker 자격 증명을 갱신합니다.
refresh_ecr_login() {
    local image=$1 registry region

    [[ -n ${image} ]] || return 0
    registry="${image%%/*}"
    if [[ ! ${registry} =~ ^[0-9]{12}\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com(\.cn)?$ ]]; then
        return 0
    fi
    region="${BASH_REMATCH[1]}"

    if ! command -v aws >/dev/null 2>&1; then
        note "ECR 인증에 필요한 aws CLI가 없습니다."
        return 1
    fi
    if ! aws ecr get-login-password --region "${region}" \
        | docker login --username AWS --password-stdin "${registry}" >/dev/null; then
        return 1
    fi
    note "ECR 로그인 갱신 ${registry}"
}

# ── 되돌릴 지점 기록 ────────────────────────────────────────────────────────
# 새 값을 쓰기 전에 지금 값을 남깁니다. 실패한 뒤에 무엇으로 돌아가야 하는지 찾기
# 시작하면 늦습니다.
log "현재 상태"
note "environment  ${DEPLOY_ENVIRONMENT}"
PREVIOUS_BACKEND_IMAGE="$(sed -n 's/^BACKEND_IMAGE=//p' "${ENV_FILE}")"
PREVIOUS_FRONTEND_IMAGE="$(sed -n 's/^FRONTEND_IMAGE=//p' "${ENV_FILE}")"
note "backend  ${PREVIOUS_BACKEND_IMAGE:-(없음)}"
note "frontend ${PREVIOUS_FRONTEND_IMAGE:-(없음)}"

ENV_BACKUP="$(mktemp)"
ENV_UPDATED="$(mktemp)"
chmod 0600 "${ENV_BACKUP}" "${ENV_UPDATED}"
cp -a "${ENV_FILE}" "${ENV_BACKUP}"
# Secret 이 들어 있는 파일의 사본입니다. 어떤 경로로 끝나든 지웁니다.
trap 'rm -f "${ENV_BACKUP}" "${ENV_UPDATED}"' EXIT

set_env_value() {
    local key=$1 value=$2
    if grep -q "^${key}=" "${ENV_FILE}"; then
        # 설정 디렉터리는 의도적으로 deploy 사용자가 새 파일을 만들 수 없습니다.
        # sed -i 는 같은 디렉터리에 임시 파일을 만들므로, /tmp 에 결과를 만든 뒤 기존
        # 파일 내용을 제자리에서 갱신합니다. 값에 / 가 들어가므로 구분자는 | 입니다.
        sed "s|^${key}=.*|${key}=${value}|" "${ENV_FILE}" > "${ENV_UPDATED}"
        cat "${ENV_UPDATED}" > "${ENV_FILE}"
    else
        printf '%s=%s\n' "${key}" "${value}" >> "${ENV_FILE}"
    fi
}

restore_env() {
    cat "${ENV_BACKUP}" > "${ENV_FILE}"
}

# ── 새 이미지 준비 ─────────────────────────────────────────────────────────
log "이미지 준비"
TARGET_FRONTEND_IMAGE="${FRONTEND_IMAGE:-${PREVIOUS_FRONTEND_IMAGE}}"
if ! refresh_ecr_login "${BACKEND_IMAGE}"; then
    fail "백엔드 이미지 ECR 로그인 갱신에 실패했습니다. 아무것도 교체하지 않았습니다."
fi

if [[ -n ${TARGET_FRONTEND_IMAGE} ]] \
    && [[ ${TARGET_FRONTEND_IMAGE%%/*} != "${BACKEND_IMAGE%%/*}" ]] \
    && ! refresh_ecr_login "${TARGET_FRONTEND_IMAGE}"; then
    fail "프론트엔드 이미지 ECR 로그인 갱신에 실패했습니다. 아무것도 교체하지 않았습니다."
fi

set_env_value BACKEND_IMAGE "${BACKEND_IMAGE}"
if [[ -n ${FRONTEND_IMAGE} ]]; then
    set_env_value FRONTEND_IMAGE "${FRONTEND_IMAGE}"
fi

# config 검증을 먼저 합니다. 설정이 깨진 채로 컨테이너를 교체하면 멀쩡히 돌던 것까지
# 내려갑니다.
if ! compose config --quiet; then
    restore_env
    fail "docker compose config 가 실패했습니다. 아무것도 교체하지 않았습니다."
fi
note "compose config 통과"

if ! compose pull --quiet backend-a backend-b gateway; then
    restore_env
    fail "이미지를 받지 못했습니다. 아무것도 교체하지 않았습니다."
fi
note "이미지 pull 완료"

# ── health 판정 ────────────────────────────────────────────────────────────
# liveness 와 전체 health 를 구분합니다.
#
# liveness 는 프로세스가 살아 있는지만 봅니다. 컨테이너 재시작 조건이 이것이어야 합니다.
# 전체 health 는 DB·Kafka·SMTP 같은 의존까지 포함하므로, 프로세스를 다시 띄운다고 풀리지
# 않는 상태에서도 DOWN 입니다. 그것을 재시작 조건으로 두면 재시작만 반복합니다.
#
# 배포 성공 판정은 readiness 로 합니다. 요청을 받을 준비가 됐는지가 교체해도 되는지의
# 기준입니다.
probe() {
    local service=$1 path=$2
    compose exec -T "${service}" \
        wget --quiet --output-document=- "http://localhost:8080${path}" 2>/dev/null || true
}

wait_until_ready() {
    local service=$1 waited=0 body=""

    while (( waited < HEALTH_TIMEOUT )); do
        body="$(probe "${service}" /actuator/health/readiness)"
        if [[ ${body} == *'"status":"UP"'* ]]; then
            note "${service} readiness UP (${waited}s)"

            body="$(probe "${service}" /actuator/health)"
            if [[ ${body} == *'"status":"UP"'* ]]; then
                note "${service} 전체 health UP"
            else
                # 배포를 막지는 않습니다. 요청은 받을 수 있고, 외부 의존 하나가 내려간
                # 것과 새 이미지가 잘못된 것은 다른 문제입니다.
                note "${service} 전체 health 가 UP 이 아닙니다. 의존 서비스를 확인하세요."
            fi
            return 0
        fi

        sleep "${HEALTH_INTERVAL}"
        waited=$(( waited + HEALTH_INTERVAL ))
    done

    note "${service} 가 ${HEALTH_TIMEOUT}s 안에 readiness UP 이 되지 않았습니다."
    compose logs --tail 50 "${service}" || true
    return 1
}

rollback() {
    local reason=$1

    log "rollback"
    note "사유: ${reason}"
    note "되돌릴 이미지: ${PREVIOUS_BACKEND_IMAGE:-(없음)}"

    if [[ -z ${PREVIOUS_BACKEND_IMAGE} ]]; then
        fail "이전 이미지가 기록돼 있지 않아 되돌릴 수 없습니다. 수동 조치가 필요합니다."
    fi

    restore_env
    compose up --detach --no-deps backend-a backend-b || true

    if wait_until_ready backend-a && wait_until_ready backend-b; then
        note "이전 이미지로 복구했습니다."
    else
        note "복구 후에도 health 가 통과하지 않습니다. 수동 조치가 필요합니다."
    fi

    record_state failed "${reason}"

    cat >&2 <<EOF

   이미 적용된 마이그레이션은 되돌리지 않았습니다. 이 스크립트의 rollback 은 이미지와
   런타임 설정까지입니다. 스키마를 되돌려야 한다면 별도 판단이 필요합니다.
EOF
    exit 1
}

record_state() {
    local result=$1 detail=${2:-}
    cat > "${STATE_FILE}" <<EOF
DEPLOY_RESULT=${result}
DEPLOY_ENVIRONMENT=${DEPLOY_ENVIRONMENT}
DEPLOY_COMMIT=${DEPLOY_COMMIT}
DEPLOYED_BACKEND_IMAGE=${BACKEND_IMAGE}
DEPLOYED_FRONTEND_IMAGE=${FRONTEND_IMAGE:-${PREVIOUS_FRONTEND_IMAGE}}
PREVIOUS_BACKEND_IMAGE=${PREVIOUS_BACKEND_IMAGE}
PREVIOUS_FRONTEND_IMAGE=${PREVIOUS_FRONTEND_IMAGE}
STARTED_AT=${STARTED_AT}
FINISHED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
DETAIL=${detail}
EOF
    chmod 0640 "${STATE_FILE}" 2>/dev/null || true
}

# ── 교체 ───────────────────────────────────────────────────────────────────
# A 를 먼저 바꿉니다. Flyway 가 기동 시점에 돌므로 마이그레이션은 A 가 적용합니다.
# 이 단계가 실패하면 B 는 그대로 두고 되돌립니다. B 는 아직 이전 이미지라 요청을 받을 수
# 있습니다.
log "backend-a 교체 (Flyway 적용)"
compose up --detach --no-deps backend-a
wait_until_ready backend-a || rollback "backend-a 가 health 를 통과하지 못했습니다."

log "backend-b 교체"
compose up --detach --no-deps backend-b
wait_until_ready backend-b || rollback "backend-b 가 health 를 통과하지 못했습니다."

if [[ -n ${FRONTEND_IMAGE} ]]; then
    log "gateway 교체"
    compose up --detach --no-deps gateway
fi

record_state succeeded

log "완료"
compose ps --format 'table {{.Service}}\t{{.Status}}' | sed 's/^/   /'
cat <<EOF

   commit   ${DEPLOY_COMMIT}
   환경     ${DEPLOY_ENVIRONMENT}
   backend  ${BACKEND_IMAGE}
   시작     ${STARTED_AT}
   종료     $(date -u +%Y-%m-%dT%H:%M:%SZ)

   기록은 ${STATE_FILE} 에 있습니다.
EOF
