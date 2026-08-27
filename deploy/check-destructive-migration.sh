#!/usr/bin/env bash
#
# 배포에 포함된 Flyway 마이그레이션이 역호환되는지 확인합니다.
#
# 배포 rollback 은 이미지와 런타임 설정을 되돌립니다. 스키마는 되돌리지 않습니다. 그래서
# 컬럼을 지우거나 이름을 바꾸는 마이그레이션이 섞이면, 이미지를 되돌려도 이전 코드가
# 없어진 컬럼을 찾습니다. 되돌렸는데 여전히 고장난 상태가 됩니다.
#
# 그런 마이그레이션이 있으면 배포를 막고, 사람이 순서를 나눠 판단하게 합니다.
#
# 사용:
#   check-destructive-migration.sh <이전_commit> <새_commit>
#
# 환경 변수:
#   ALLOW_DESTRUCTIVE_MIGRATION=true  확인했고 진행한다는 뜻입니다

set -euo pipefail

MIGRATION_DIR="${MIGRATION_DIR:-src/main/resources/db/migration}"

# 되돌릴 수 없는 변경입니다. 이전 코드가 참조하던 것이 사라지거나 이름이 바뀝니다.
#
# ADD COLUMN, CREATE TABLE, CREATE INDEX 는 여기 없습니다. 이전 코드는 새로 생긴 것을
# 모르므로 그대로 동작합니다.
DESTRUCTIVE_PATTERNS='DROP[[:space:]]+TABLE|DROP[[:space:]]+COLUMN|DROP[[:space:]]+CONSTRAINT|RENAME[[:space:]]+TO|RENAME[[:space:]]+COLUMN|ALTER[[:space:]]+COLUMN[[:space:]]+[a-z_]+[[:space:]]+TYPE|SET[[:space:]]+NOT[[:space:]]+NULL|TRUNCATE'

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }

if [[ $# -ne 2 ]]; then
    echo "사용: $0 <이전_commit> <새_commit>" >&2
    exit 2
fi

PREVIOUS=$1
CURRENT=$2

log "마이그레이션 비교 ${PREVIOUS} → ${CURRENT}"

# 이전 배포 시점을 알 수 없으면 판단할 근거가 없습니다. 통과시키지 않습니다. 모르는
# 것을 괜찮다고 답하면 확인 절차가 있으나 마나입니다.
if ! git cat-file -e "${PREVIOUS}^{commit}" 2>/dev/null; then
    note "이전 commit ${PREVIOUS} 를 찾을 수 없습니다."
    note "얕은 clone 이면 fetch-depth 를 늘리세요."
    exit 1
fi

ADDED="$(git diff --name-only --diff-filter=A "${PREVIOUS}" "${CURRENT}" -- "${MIGRATION_DIR}" || true)"

if [[ -z ${ADDED} ]]; then
    note "새 마이그레이션이 없습니다."
    exit 0
fi

note "새 마이그레이션:"
while IFS= read -r f; do note "  ${f}"; done <<< "${ADDED}"

FINDINGS=""
while IFS= read -r f; do
    [[ -n ${f} ]] || continue
    # 주석은 뺍니다. 설명에 DROP 이라는 단어가 있다고 막을 이유가 없습니다.
    body="$(git show "${CURRENT}:${f}" | sed 's/--.*$//')"
    hits="$(grep -inE "${DESTRUCTIVE_PATTERNS}" <<< "${body}" || true)"
    if [[ -n ${hits} ]]; then
        FINDINGS+="${f}"$'\n'
        while IFS= read -r h; do FINDINGS+="    ${h}"$'\n'; done <<< "${hits}"
    fi
done <<< "${ADDED}"

if [[ -z ${FINDINGS} ]]; then
    log "통과"
    note "역호환되지 않는 변경을 찾지 못했습니다."
    exit 0
fi

log "역호환되지 않는 마이그레이션"
printf '%s' "${FINDINGS}" | sed 's/^/   /'

if [[ ${ALLOW_DESTRUCTIVE_MIGRATION:-false} == "true" ]]; then
    note ""
    note "ALLOW_DESTRUCTIVE_MIGRATION=true 이므로 진행합니다."
    note "이 배포가 실패해도 이미지 rollback 만으로는 복구되지 않습니다."
    exit 0
fi

cat >&2 <<EOF

   배포를 막았습니다.

   이미지를 되돌려도 스키마는 되돌아가지 않습니다. 이전 코드가 없어진 컬럼이나 바뀐
   이름을 찾게 되므로, rollback 한 뒤에도 고장난 상태가 그대로입니다.

   보통은 두 번으로 나눕니다.

     1) 새 코드가 옛 컬럼 없이도 동작하도록 배포합니다
     2) 그 배포가 안정된 뒤에 컬럼을 지우는 마이그레이션을 배포합니다

   나눌 수 없다고 판단했다면 ALLOW_DESTRUCTIVE_MIGRATION=true 로 다시 실행하세요.
   그 경우 실패 시 복구는 수동입니다.
EOF
exit 1
