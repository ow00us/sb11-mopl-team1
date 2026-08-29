#!/usr/bin/env bash
# ensure-aws-cli.sh의 멱등성, root 경계, 설치 성공·실패를 호스트 변경 없이 검증합니다.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENSURE_SCRIPT="${SCRIPT_DIR}/ensure-aws-cli.sh"
TEST_ROOT="$(mktemp -d)"
ORIGINAL_PATH="${PATH}"
trap 'rm -rf "${TEST_ROOT}"' EXIT

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

assert_contains() {
    local name=$1 expected=$2 actual=$3
    [[ ${actual} == *"${expected}"* ]] || fail "${name}: ${expected}"
    pass "${name}"
}

new_case() {
    local name=$1
    CASE_DIR="${TEST_ROOT}/${name}"
    BIN_DIR="${CASE_DIR}/bin"
    mkdir -p "${BIN_DIR}"
    export TEST_CASE_DIR="${CASE_DIR}" TEST_BIN_DIR="${BIN_DIR}"
    # GitHub 러너에 이미 설치된 시스템 aws를 우연히 발견하지 않고, 각 케이스가
    # 만든 실행 파일만 확인합니다.
    export AWS_CLI_COMMAND="${BIN_DIR}/aws"
}

fake_root_id() {
    cat > "${BIN_DIR}/id" <<'EOF'
#!/usr/bin/env bash
[[ ${1:-} == -u ]] && { echo 0; exit 0; }
exec /usr/bin/id "$@"
EOF
    chmod +x "${BIN_DIR}/id"
}

fake_non_root_id() {
    cat > "${BIN_DIR}/id" <<'EOF'
#!/usr/bin/env bash
[[ ${1:-} == -u ]] && { echo 1000; exit 0; }
exec /usr/bin/id "$@"
EOF
    chmod +x "${BIN_DIR}/id"
}

fake_installing_curl() {
    cat > "${BIN_DIR}/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
output=''
while (($#)); do
    if [[ $1 == -o ]]; then
        output=$2
        shift 2
    else
        shift
    fi
done
[[ -n ${output} ]]
cat > "${output}" <<'INSTALLER'
#!/usr/bin/env bash
printf '%s\n' "$*" > "${TEST_CASE_DIR}/install.args"
cat > "${TEST_BIN_DIR}/aws" <<'AWS'
#!/usr/bin/env bash
echo 'aws-cli/2.99.0 Python/3.13.0 Linux/test exe/x86_64.test'
AWS
chmod +x "${TEST_BIN_DIR}/aws"
INSTALLER
chmod +x "${output}"
EOF
    chmod +x "${BIN_DIR}/curl"
}

new_case existing
fake_root_id
cat > "${BIN_DIR}/aws" <<'EOF'
#!/usr/bin/env bash
echo 'aws-cli/2.98.0 Python/3.13.0 Linux/test exe/x86_64.test'
EOF
chmod +x "${BIN_DIR}/aws"
output="$(PATH="${BIN_DIR}:${ORIGINAL_PATH}" bash "${ENSURE_SCRIPT}")"
assert_contains "기존 v2는 설치를 건너뜀" "AWS CLI v2가 이미 있습니다" "${output}"
[[ ! -e ${CASE_DIR}/install.args ]] || fail "기존 v2에서 설치기를 실행함"

new_case install
fake_root_id
fake_installing_curl
output="$(PATH="${BIN_DIR}:${ORIGINAL_PATH}" bash "${ENSURE_SCRIPT}")"
assert_contains "공식 설치기를 system 모드로 실행" "--system" "$(<"${CASE_DIR}/install.args")"
assert_contains "설치 후 v2 확인" "AWS CLI v2 설치 완료" "${output}"

new_case non-root
fake_non_root_id
fake_installing_curl
if output="$(PATH="${BIN_DIR}:${ORIGINAL_PATH}" bash "${ENSURE_SCRIPT}" 2>&1)"; then
    fail "비 root 실행이 성공함"
fi
assert_contains "비 root 실행 거절" "root로 실행해야 합니다" "${output}"
[[ ! -e ${CASE_DIR}/install.args ]] || fail "비 root에서 설치기를 실행함"

new_case download-failure
fake_root_id
cat > "${BIN_DIR}/curl" <<'EOF'
#!/usr/bin/env bash
exit 22
EOF
chmod +x "${BIN_DIR}/curl"
if output="$(PATH="${BIN_DIR}:${ORIGINAL_PATH}" bash "${ENSURE_SCRIPT}" 2>&1)"; then
    fail "다운로드 실패가 성공으로 처리됨"
fi
assert_contains "다운로드 실패 전달" "공식 설치 스크립트를 받지 못했습니다" "${output}"

printf 'PASS: AWS CLI 준비 스크립트 전체 검증\n'
