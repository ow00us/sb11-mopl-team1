#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
IMPORT_SCRIPT=${SCRIPT_DIR}/import.sh
IMPORT_SQL=${SCRIPT_DIR}/import.sql
VERIFY_SQL=${SCRIPT_DIR}/verify.sql
BACKFILL_SQL=${SCRIPT_DIR}/backfill-elasticsearch.sql

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

bash -n "${IMPORT_SCRIPT}"
bash "${IMPORT_SCRIPT}" --help >/dev/null
grep -q -- '--preflight-only' "${IMPORT_SCRIPT}" \
    || fail "비파괴 사전 검증 옵션이 없습니다."

if bash "${IMPORT_SCRIPT}" \
    --dataset-dir /not-used \
    --dataset-version v1 \
    --expected-instance-id i-test \
    --confirm WRONG >/dev/null 2>&1; then
    fail "잘못된 초기화 확인 문자열을 허용했습니다."
fi

grep -q '^BEGIN;$' "${IMPORT_SQL}" || fail "import.sql에 BEGIN이 없습니다."
grep -q '^COMMIT;$' "${IMPORT_SQL}" || fail "import.sql에 COMMIT이 없습니다."
grep -q "table_name <> 'flyway_schema_history'" "${IMPORT_SQL}" \
    || fail "Flyway 이력 보존 조건이 없습니다."

for table in \
    users contents content_tags reviews playlists playlist_contents \
    playlist_subscriptions follows conversations conversation_participants \
    direct_messages notifications; do
    grep -q "\\copy ${table} " "${IMPORT_SQL}" \
        || fail "${table} COPY가 없습니다."
    grep -q "SELECT '${table}='" "${VERIFY_SQL}" \
        || fail "${table} 행 수 검증이 없습니다."
done

for key in \
    content_aggregate_mismatches playlist_aggregate_mismatches \
    conversation_participant_mismatches conversation_pair_key_mismatches \
    dm_sender_mismatches dm_sequence_mismatches notification_mapping_mismatches; do
    grep -q "${key}" "${VERIFY_SQL}" || fail "${key} 검증이 없습니다."
done

for field in \
    contentId title description type tags averageRating watcherCount reviewCount \
    thumbnailUrl createdAt createdAtEpochMicros; do
    grep -q "'${field}'" "${BACKFILL_SQL}" \
        || fail "Elasticsearch 문서에 ${field} 필드가 없습니다."
done

grep -q "'_index', 'contents'" "${BACKFILL_SQL}" \
    || fail "Elasticsearch Bulk index action이 없습니다."

printf 'PASS: staging dataset import safety checks\n'
