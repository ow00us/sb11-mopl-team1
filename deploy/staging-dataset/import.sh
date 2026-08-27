#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
COMPOSE_FILE=${COMPOSE_FILE:-/srv/mopl/app/docker-compose.prod.yml}
ENV_FILE=${ENV_FILE:-/etc/mopl/staging.env}
EXPECTED_DOMAIN=${EXPECTED_DOMAIN:-mopl-team1-staging.duckdns.org}
CONTAINER_DATASET_DIR=/tmp/mopl-staging-dataset
BACKUP_DIR=${BACKUP_DIR:-/srv/mopl/backups}
RUN_DIR=${RUN_DIR:-/srv/mopl/dataset-runs}

DATASET_DIR=
DATASET_VERSION=
EXPECTED_INSTANCE_ID=
CONFIRMATION=
PREFLIGHT_ONLY=false
RESTORE_SERVICES=false
POSTGRES_CONTAINER=
CONTAINER_DATASET_PREPARED=false
HOST_BULK_FILE=
ELASTICSEARCH_CONTAINER=

usage() {
    cat <<'EOF'
사용:
  import.sh \
    --dataset-dir /path/to/datasets/v1 \
    --dataset-version v1 \
    --expected-instance-id i-0123456789abcdef0 \
    --confirm RESET-STAGING \
    [--preflight-only]

v1과 v2는 누적하지 않습니다. 이 명령은 스테이징의 기존 애플리케이션 데이터를
백업한 뒤 모두 비우고 선택한 데이터셋 하나를 단독으로 주입합니다.
EOF
}

log() { printf '\n== %s ==\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dataset-dir) DATASET_DIR=${2:-}; shift 2 ;;
        --dataset-version) DATASET_VERSION=${2:-}; shift 2 ;;
        --expected-instance-id) EXPECTED_INSTANCE_ID=${2:-}; shift 2 ;;
        --confirm) CONFIRMATION=${2:-}; shift 2 ;;
        --preflight-only) PREFLIGHT_ONLY=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "알 수 없는 인자입니다: $1" ;;
    esac
done

[[ -n ${DATASET_DIR} ]] || fail "--dataset-dir가 필요합니다."
[[ ${DATASET_VERSION} == v1 || ${DATASET_VERSION} == v2 ]] \
    || fail "--dataset-version은 v1 또는 v2여야 합니다."
[[ -n ${EXPECTED_INSTANCE_ID} ]] || fail "--expected-instance-id가 필요합니다."
[[ ${CONFIRMATION} == RESET-STAGING ]] \
    || fail "스테이징 초기화를 승인하려면 --confirm RESET-STAGING을 지정하세요."
[[ -d ${DATASET_DIR} ]] || fail "데이터셋 디렉터리를 찾지 못했습니다: ${DATASET_DIR}"
[[ -r ${ENV_FILE} ]] || fail "staging 환경 파일을 읽을 수 없습니다: ${ENV_FILE}"
[[ -r ${COMPOSE_FILE} ]] || fail "Compose 파일을 읽을 수 없습니다: ${COMPOSE_FILE}"

compose() {
    docker compose --file "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"
}

env_value() {
    sed -n "s/^$1=//p" "${ENV_FILE}" | tail -n 1 | tr -d '\r'
}

cleanup() {
    if [[ -n ${POSTGRES_CONTAINER} && ${CONTAINER_DATASET_PREPARED} == true ]]; then
        docker exec "${POSTGRES_CONTAINER}" rm -rf "${CONTAINER_DATASET_DIR}" >/dev/null 2>&1 || true
    fi
    if [[ -n ${ELASTICSEARCH_CONTAINER} ]]; then
        docker exec --user 0 "${ELASTICSEARCH_CONTAINER}" \
            rm -f /tmp/mopl-contents.ndjson >/dev/null 2>&1 || true
    fi
    if [[ -n ${HOST_BULK_FILE} ]]; then
        rm -f -- "${HOST_BULK_FILE}" >/dev/null 2>&1 || true
    fi
    if [[ ${RESTORE_SERVICES} == true ]]; then
        compose up -d backend-a backend-b >/dev/null 2>&1 || true
        compose up -d gateway caddy >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

log "대상 환경 확인"
TOKEN=$(curl --fail --silent --show-error --request PUT \
    --header 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
    http://169.254.169.254/latest/api/token)
ACTUAL_INSTANCE_ID=$(curl --fail --silent --show-error \
    --header "X-aws-ec2-metadata-token: ${TOKEN}" \
    http://169.254.169.254/latest/meta-data/instance-id)
[[ ${ACTUAL_INSTANCE_ID} == "${EXPECTED_INSTANCE_ID}" ]] \
    || fail "EC2 인스턴스가 다릅니다. actual=${ACTUAL_INSTANCE_ID} expected=${EXPECTED_INSTANCE_ID}"

ACTUAL_DOMAIN=$(env_value MOPL_DOMAIN)
[[ ${ACTUAL_DOMAIN} == "${EXPECTED_DOMAIN}" ]] \
    || fail "staging 도메인이 아닙니다. actual=${ACTUAL_DOMAIN}"
DEPLOY_ENVIRONMENT=$(sed -n 's/^DEPLOY_ENVIRONMENT=//p' /etc/mopl/deploy-state.env \
    | tail -n 1 | tr -d '\r')
[[ ${DEPLOY_ENVIRONMENT} == staging ]] \
    || fail "최근 배포 환경 기록이 staging이 아닙니다: ${DEPLOY_ENVIRONMENT:-missing}"

DATASET_MANIFEST=${DATASET_DIR}/manifest.yml
[[ -r ${DATASET_MANIFEST} ]] || fail "manifest.yml이 없습니다."
grep -qx 'status: validated' "${DATASET_MANIFEST}" || fail "검증 완료 데이터셋이 아닙니다."
grep -qx 'flywayVersion: 20' "${DATASET_MANIFEST}" || fail "데이터셋 Flyway 버전이 20이 아닙니다."
grep -Eq "^datasetVersion: ${DATASET_VERSION}-validation-[0-9]+pct$" "${DATASET_MANIFEST}" \
    || fail "데이터셋 버전과 디렉터리가 일치하지 않습니다."

DATASET_BACKEND_SHA=$(sed -n 's/^backendGitSha: //p' "${DATASET_MANIFEST}")
DEPLOYED_BACKEND_SHA=$(sed -n 's/^DEPLOY_COMMIT=//p' /etc/mopl/deploy-state.env | tail -n 1 | tr -d '\r')
[[ -n ${DATASET_BACKEND_SHA} && ${DATASET_BACKEND_SHA} == "${DEPLOYED_BACKEND_SHA}" ]] \
    || fail "데이터셋과 배포 백엔드 SHA가 다릅니다. dataset=${DATASET_BACKEND_SHA} deployed=${DEPLOYED_BACKEND_SHA}"

REQUIRED_FILES=(
    users.csv contents.csv content_tags.csv reviews.csv playlists.csv
    playlist_contents.csv playlist_subscriptions.csv follows.csv conversations.csv
    conversation_participants.csv direct_messages.csv notifications.csv
    checksums.sha256 validation-report.json
)
for file in "${REQUIRED_FILES[@]}"; do
    [[ -r ${DATASET_DIR}/${file} ]] || fail "필수 파일이 없습니다: ${file}"
done
(cd "${DATASET_DIR}" && sha256sum --check --strict checksums.sha256)
grep -q '"status": "PASS"' "${DATASET_DIR}/validation-report.json" \
    || fail "validation-report.json이 PASS가 아닙니다."

POSTGRES_USER=$(env_value POSTGRES_USER)
POSTGRES_DB=$(env_value POSTGRES_DB)
[[ -n ${POSTGRES_USER} && -n ${POSTGRES_DB} ]] || fail "PostgreSQL 설정을 찾지 못했습니다."

POSTGRES_CONTAINER=$(compose ps -q postgres)
[[ -n ${POSTGRES_CONTAINER} ]] || fail "PostgreSQL 컨테이너가 실행 중이 아닙니다."
FLYWAY_VERSION=$(compose exec -T postgres psql -v ON_ERROR_STOP=1 \
    -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc \
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")
[[ ${FLYWAY_VERSION} == 20 ]] || fail "staging Flyway 버전이 20이 아닙니다: ${FLYWAY_VERSION}"

if [[ ${PREFLIGHT_ONLY} == true ]]; then
    log "사전 검증 완료"
    printf 'instance_id=%s\ndomain=%s\ndataset_version=%s\nbackend_sha=%s\nflyway_version=%s\n' \
        "${ACTUAL_INSTANCE_ID}" "${ACTUAL_DOMAIN}" "${DATASET_VERSION}" \
        "${DATASET_BACKEND_SHA}" "${FLYWAY_VERSION}"
    exit 0
fi

RUN_AT=$(date -u +%Y%m%dT%H%M%SZ)
install -d -m 0750 "${BACKUP_DIR}" "${RUN_DIR}"
BACKUP_FILE=${BACKUP_DIR}/before-${DATASET_VERSION}-${RUN_AT}.dump
REPORT_FILE=${RUN_DIR}/${RUN_AT}-${DATASET_VERSION}.txt

log "주입 전 PostgreSQL 백업"
compose exec -T postgres pg_dump -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    --format=custom --no-owner --no-acl > "${BACKUP_FILE}"
[[ -s ${BACKUP_FILE} ]] || fail "백업 파일이 비어 있습니다."

log "애플리케이션 요청 차단"
RESTORE_SERVICES=true
compose stop caddy gateway backend-a backend-b >/dev/null

log "데이터셋을 PostgreSQL 컨테이너로 복사"
docker exec "${POSTGRES_CONTAINER}" rm -rf "${CONTAINER_DATASET_DIR}"
docker exec "${POSTGRES_CONTAINER}" mkdir -p "${CONTAINER_DATASET_DIR}"
CONTAINER_DATASET_PREPARED=true
docker cp "${DATASET_DIR}/." "${POSTGRES_CONTAINER}:${CONTAINER_DATASET_DIR}"
docker cp "${SCRIPT_DIR}/import.sql" "${POSTGRES_CONTAINER}:${CONTAINER_DATASET_DIR}/import.sql"
docker cp "${SCRIPT_DIR}/verify.sql" "${POSTGRES_CONTAINER}:${CONTAINER_DATASET_DIR}/verify.sql"
docker cp "${SCRIPT_DIR}/backfill-elasticsearch.sql" \
    "${POSTGRES_CONTAINER}:${CONTAINER_DATASET_DIR}/backfill-elasticsearch.sql"

log "PostgreSQL 단일 트랜잭션 주입"
docker exec -i "${POSTGRES_CONTAINER}" psql -v ON_ERROR_STOP=1 \
    -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -f "${CONTAINER_DATASET_DIR}/import.sql"

log "휘발 상태와 검색 인덱스 초기화"
compose exec -T redis redis-cli FLUSHDB >/dev/null
compose exec -T elasticsearch sh -c \
    "curl --silent --show-error --output /dev/null --write-out '%{http_code}' --request DELETE http://localhost:9200/contents" \
    | grep -Eq '^(200|404)$'

log "백엔드 재기동과 Elasticsearch 백필"
compose up -d backend-a backend-b >/dev/null
DB_CONTENT_COUNT=$(compose exec -T postgres psql -v ON_ERROR_STOP=1 \
    -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -Atc 'SELECT COUNT(*) FROM contents')

INDEX_READY=false
for _ in $(seq 1 30); do
    status=$(compose exec -T elasticsearch sh -c \
        "curl --silent --output /dev/null --write-out '%{http_code}' http://localhost:9200/contents" \
        2>/dev/null || true)
    if [[ ${status} == 200 ]]; then
        INDEX_READY=true
        break
    fi
    sleep 2
done
[[ ${INDEX_READY} == true ]] || fail "Elasticsearch contents 인덱스가 생성되지 않았습니다."

log "PostgreSQL 콘텐츠를 Elasticsearch Bulk 형식으로 변환"
HOST_BULK_FILE=$(mktemp /tmp/mopl-contents.XXXXXX.ndjson)
docker exec -i "${POSTGRES_CONTAINER}" psql -v ON_ERROR_STOP=1 \
    -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -At \
    -f "${CONTAINER_DATASET_DIR}/backfill-elasticsearch.sql" > "${HOST_BULK_FILE}"
[[ -s ${HOST_BULK_FILE} ]] || fail "Elasticsearch Bulk 파일이 비어 있습니다."
chmod 0644 "${HOST_BULK_FILE}"

ELASTICSEARCH_CONTAINER=$(compose ps -q elasticsearch)
[[ -n ${ELASTICSEARCH_CONTAINER} ]] || fail "Elasticsearch 컨테이너가 실행 중이 아닙니다."
docker cp "${HOST_BULK_FILE}" "${ELASTICSEARCH_CONTAINER}:/tmp/mopl-contents.ndjson"
BULK_RESULT=$(compose exec -T elasticsearch sh -c \
    "curl --silent --show-error --fail \
      --header 'Content-Type: application/x-ndjson' \
      --data-binary @/tmp/mopl-contents.ndjson \
      'http://localhost:9200/_bulk?refresh=true&filter_path=errors'")
[[ ${BULK_RESULT} == *'"errors":false'* ]] \
    || fail "Elasticsearch Bulk 색인에 실패했습니다: ${BULK_RESULT}"
docker exec --user 0 "${ELASTICSEARCH_CONTAINER}" rm -f /tmp/mopl-contents.ndjson
ELASTICSEARCH_CONTAINER=
rm -f -- "${HOST_BULK_FILE}"
HOST_BULK_FILE=

ES_CONTENT_COUNT=-1
for _ in $(seq 1 30); do
    ES_CONTENT_COUNT=$(compose exec -T elasticsearch sh -c \
        "curl --silent --fail http://localhost:9200/contents/_count" 2>/dev/null \
        | sed -n 's/.*"count":\([0-9][0-9]*\).*/\1/p' || true)
    if [[ ${ES_CONTENT_COUNT:-} == "${DB_CONTENT_COUNT}" ]]; then
        break
    fi
    sleep 2
done
[[ ${ES_CONTENT_COUNT:-} == "${DB_CONTENT_COUNT}" ]] \
    || fail "Elasticsearch 백필 수가 일치하지 않습니다. db=${DB_CONTENT_COUNT} es=${ES_CONTENT_COUNT:-unknown}"

log "관계와 집계 검증"
VERIFY_OUTPUT=$(docker exec -i "${POSTGRES_CONTAINER}" psql -v ON_ERROR_STOP=1 \
    -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" -At \
    -f "${CONTAINER_DATASET_DIR}/verify.sql")
printf '%s\n' "${VERIFY_OUTPUT}"

if [[ ${DATASET_VERSION} == v1 ]]; then
    declare -A EXPECTED_COUNTS=(
        [users]=400 [contents]=4000 [content_tags]=12000 [reviews]=20000
        [playlists]=1000 [playlist_contents]=10000 [playlist_subscriptions]=4000
        [follows]=10000 [conversations]=400 [conversation_participants]=800
        [direct_messages]=10000 [notifications]=4000
    )
else
    declare -A EXPECTED_COUNTS=(
        [users]=1000 [contents]=10000 [content_tags]=30000 [reviews]=50000
        [playlists]=2500 [playlist_contents]=25000 [playlist_subscriptions]=10000
        [follows]=25000 [conversations]=1000 [conversation_participants]=2000
        [direct_messages]=25000 [notifications]=10000
    )
fi

for key in "${!EXPECTED_COUNTS[@]}"; do
    actual=$(printf '%s\n' "${VERIFY_OUTPUT}" | sed -n "s/^${key}=//p")
    [[ ${actual} == "${EXPECTED_COUNTS[$key]}" ]] \
        || fail "행 수가 다릅니다. ${key}: actual=${actual:-missing} expected=${EXPECTED_COUNTS[$key]}"
done

MISMATCH_KEYS=(
    content_aggregate_mismatches playlist_aggregate_mismatches
    conversation_participant_mismatches conversation_pair_key_mismatches
    dm_sender_mismatches dm_sequence_mismatches notification_mapping_mismatches
)
for key in "${MISMATCH_KEYS[@]}"; do
    actual=$(printf '%s\n' "${VERIFY_OUTPUT}" | sed -n "s/^${key}=//p")
    [[ ${actual} == 0 ]] || fail "검증 불일치가 있습니다. ${key}=${actual:-missing}"
done

log "게이트웨이 재기동"
compose up -d gateway caddy >/dev/null
RESTORE_SERVICES=false

{
    printf 'status=PASS\n'
    printf 'dataset_version=%s\n' "${DATASET_VERSION}"
    printf 'dataset_backend_sha=%s\n' "${DATASET_BACKEND_SHA}"
    printf 'instance_id=%s\n' "${ACTUAL_INSTANCE_ID}"
    printf 'domain=%s\n' "${ACTUAL_DOMAIN}"
    printf 'flyway_version=%s\n' "${FLYWAY_VERSION}"
    printf 'backup_file=%s\n' "${BACKUP_FILE}"
    printf 'postgres_content_count=%s\n' "${DB_CONTENT_COUNT}"
    printf 'elasticsearch_content_count=%s\n' "${ES_CONTENT_COUNT}"
    printf '%s\n' "${VERIFY_OUTPUT}"
} > "${REPORT_FILE}"
chmod 0640 "${REPORT_FILE}" "${BACKUP_FILE}"

docker exec "${POSTGRES_CONTAINER}" rm -rf "${CONTAINER_DATASET_DIR}"
CONTAINER_DATASET_PREPARED=false
POSTGRES_CONTAINER=

log "완료"
printf 'report=%s\nbackup=%s\n' "${REPORT_FILE}" "${BACKUP_FILE}"
