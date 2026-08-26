#!/usr/bin/env bash
#
# deploy.sh 의 성공·실패·rollback 경로를 격리된 환경에서 확인합니다.
#
# docker 를 가짜로 바꿔 놓고 돌립니다. 실제 서버에서만 확인할 수 있는 절차라면 고칠
# 때마다 서버가 필요하고, 그러면 rollback 경로는 사실상 한 번도 확인되지 않습니다.
# 정작 필요한 순간에 처음 돌아가는 코드가 됩니다.
#
# 사용:
#   bash deploy/test-deploy.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_SH="${SCRIPT_DIR}/deploy.sh"

PASS=0
FAIL=0

pass() { printf '   \033[32mPASS\033[0m %s\n' "$*"; PASS=$(( PASS + 1 )); }
bad()  { printf '   \033[31mFAIL\033[0m %s\n' "$*"; FAIL=$(( FAIL + 1 )); }
head_() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

check() {
    local label=$1 expected=$2 actual=$3
    if [[ ${actual} == "${expected}" ]]; then
        pass "${label}"
    else
        bad "${label} — 기대 '${expected}', 실제 '${actual}'"
    fi
}

contains() {
    local label=$1 needle=$2 haystack=$3
    if [[ ${haystack} == *"${needle}"* ]]; then
        pass "${label}"
    else
        bad "${label} — '${needle}' 가 출력에 없습니다"
    fi
}

# 가짜 docker 를 만듭니다. HEALTH_ANSWER 로 exec 응답을 정하고, 호출된 compose 인자를
# MOCK_LOG 에 남깁니다.
#
# $1 = down_when_new  : env 파일에 sha256:NEW 가 있으면 DOWN, 아니면 UP
#      always_up      : 항상 UP
#      config_fails   : compose config 를 실패시킴
make_docker() {
    local mode=$1 dir=$2
    mkdir -p "${dir}/bin"
    cat > "${dir}/bin/docker" <<EOF
#!/usr/bin/env bash
mode=${mode}
EOF
    cat >> "${dir}/bin/docker" <<'EOF'
[[ "$1" == "compose" ]] || exit 0
shift
args=("$@")

envfile=""
for i in "${!args[@]}"; do
  [[ "${args[$i]}" == "--env-file" ]] && envfile="${args[$((i+1))]}"
done

for a in "${args[@]}"; do
  case "$a" in
    config)
      [[ $mode == config_fails ]] && exit 1
      exit 0 ;;
    exec)
      if [[ $mode == down_when_new ]] && grep -q "sha256:NEW" "$envfile"; then
        echo '{"status":"DOWN"}'
      else
        echo '{"status":"UP"}'
      fi
      exit 0 ;;
    logs) exit 0 ;;
    ps)   exit 0 ;;
  esac
done

echo "${args[*]}" >> "$MOCK_LOG"
exit 0
EOF
    chmod +x "${dir}/bin/docker"
}

new_workspace() {
    local dir
    dir="$(mktemp -d)"
    printf 'BACKEND_IMAGE=reg/be@sha256:OLD\nFRONTEND_IMAGE=reg/fe@sha256:OLDFE\nJWT_SECRET=keep-me\n' \
        > "${dir}/prod.env"
    : > "${dir}/compose.yml"
    : > "${dir}/state.env"
    : > "${dir}/calls.log"
    echo "${dir}"
}

run_deploy() {
    local dir=$1; shift
    MOCK_LOG="${dir}/calls.log" \
    PATH="${dir}/bin:${PATH}" \
    COMPOSE_FILE="${dir}/compose.yml" \
    ENV_FILE="${dir}/prod.env" \
    STATE_FILE="${dir}/state.env" \
    HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-6}" \
    HEALTH_INTERVAL="${HEALTH_INTERVAL:-2}" \
        bash "${DEPLOY_SH}" "$@" 2>&1
}

# ── 1. 성공 ────────────────────────────────────────────────────────────────
head_ "성공: A 를 먼저, B 를 나중에 교체한다"
W="$(new_workspace)"; make_docker always_up "${W}"
OUT="$(run_deploy "${W}" --backend-image reg/be@sha256:NEW --commit abc1234)"
check "종료 코드 0" 0 $?

ORDER="$(grep -oE 'no-deps backend-[ab]' "${W}/calls.log" | tr '\n' ' ')"
check "교체 순서" "no-deps backend-a no-deps backend-b " "${ORDER}"
check "env 에 새 이미지" "reg/be@sha256:NEW" "$(sed -n 's/^BACKEND_IMAGE=//p' "${W}/prod.env")"
check "결과 기록" "succeeded" "$(sed -n 's/^DEPLOY_RESULT=//p' "${W}/state.env")"
check "되돌릴 지점 기록" "reg/be@sha256:OLD" "$(sed -n 's/^PREVIOUS_BACKEND_IMAGE=//p' "${W}/state.env")"
check "commit 기록" "abc1234" "$(sed -n 's/^DEPLOY_COMMIT=//p' "${W}/state.env")"
rm -rf "${W}"

# ── 2. rollback ────────────────────────────────────────────────────────────
head_ "실패: A 가 health 를 통과하지 못하면 되돌리고 B 는 건드리지 않는다"
W="$(new_workspace)"; make_docker down_when_new "${W}"
OUT="$(run_deploy "${W}" --backend-image reg/be@sha256:NEW --commit abc1234)"
check "종료 코드 1" 1 $?

check "env 가 이전 이미지로" "reg/be@sha256:OLD" "$(sed -n 's/^BACKEND_IMAGE=//p' "${W}/prod.env")"
check "Secret 보존" "keep-me" "$(sed -n 's/^JWT_SECRET=//p' "${W}/prod.env")"
check "결과 기록" "failed" "$(sed -n 's/^DEPLOY_RESULT=//p' "${W}/state.env")"
# rollback 은 두 인스턴스를 함께 되돌리므로, 교체 단계에서 B 를 건드렸는지만 봅니다.
check "교체 단계에서 B 를 건드리지 않음" 0 "$(grep -c 'no-deps backend-b$' "${W}/calls.log")"
contains "복구 확인" "이전 이미지로 복구했습니다" "${OUT}"
contains "스키마는 되돌리지 않았음을 알림" "마이그레이션은 되돌리지 않았습니다" "${OUT}"
rm -rf "${W}"

# ── 3. 교체 전 차단 ────────────────────────────────────────────────────────
head_ "설정이 깨지면 아무것도 교체하지 않는다"
W="$(new_workspace)"; make_docker config_fails "${W}"
OUT="$(run_deploy "${W}" --backend-image reg/be@sha256:NEW)"
check "종료 코드 1" 1 $?
check "env 그대로" "reg/be@sha256:OLD" "$(sed -n 's/^BACKEND_IMAGE=//p' "${W}/prod.env")"
check "컨테이너를 건드리지 않음" 0 "$(grep -c 'no-deps' "${W}/calls.log")"
rm -rf "${W}"

head_ "이동 태그는 배포 대상이 될 수 없다"
W="$(new_workspace)"; make_docker always_up "${W}"
OUT="$(run_deploy "${W}" --backend-image reg/be:main)"
check "종료 코드 1" 1 $?
contains "이유 안내" "이동 태그" "${OUT}"
check "컨테이너를 건드리지 않음" 0 "$(grep -c 'no-deps' "${W}/calls.log")"
rm -rf "${W}"

# ── 결과 ───────────────────────────────────────────────────────────────────
head_ "결과"
printf '   통과 %d, 실패 %d\n\n' "${PASS}" "${FAIL}"
(( FAIL == 0 ))
