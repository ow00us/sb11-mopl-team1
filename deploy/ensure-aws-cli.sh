#!/usr/bin/env bash
#
# ECR 인증에 사용하는 AWS CLI v2를 호스트에 준비합니다.
# SSM의 root 단계와 bootstrap.sh가 함께 호출하며, 이미 설치돼 있으면 아무것도 바꾸지 않습니다.

set -euo pipefail

AWS_CLI_INSTALL_URL="${AWS_CLI_INSTALL_URL:-https://awscli.amazonaws.com/v2/install.sh}"
AWS_CLI_COMMAND="${AWS_CLI_COMMAND:-aws}"
AWS_CLI_UNZIP_COMMAND="${AWS_CLI_UNZIP_COMMAND:-unzip}"

note() { printf '   %s\n' "$*"; }
fail() { printf '\n\033[31m!! %s\033[0m\n' "$*" >&2; exit 1; }

aws_cli_version() {
    "${AWS_CLI_COMMAND}" --version 2>&1
}

if command -v "${AWS_CLI_COMMAND}" >/dev/null 2>&1 \
    && aws_cli_version | grep -Eq '^aws-cli/2\.'; then
    note "AWS CLI v2가 이미 있습니다: $(aws_cli_version)"
    exit 0
fi

[[ $(id -u) -eq 0 ]] || fail "AWS CLI v2 설치는 root로 실행해야 합니다."

missing_packages=()
if ! command -v curl >/dev/null 2>&1; then
    missing_packages+=(ca-certificates curl)
fi
if ! command -v "${AWS_CLI_UNZIP_COMMAND}" >/dev/null 2>&1; then
    missing_packages+=(unzip)
fi

if ((${#missing_packages[@]})); then
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${missing_packages[@]}"
fi

aws_cli_installer="$(mktemp)"
trap 'rm -f "${aws_cli_installer}"' EXIT

curl --proto '=https' --tlsv1.2 -fsSL \
    "${AWS_CLI_INSTALL_URL}" -o "${aws_cli_installer}" \
    || fail "AWS CLI v2 공식 설치 스크립트를 받지 못했습니다."

bash "${aws_cli_installer}" --system \
    || fail "AWS CLI v2 시스템 설치에 실패했습니다."

hash -r
command -v "${AWS_CLI_COMMAND}" >/dev/null 2>&1 \
    || fail "AWS CLI v2 설치 후 실행 파일을 찾지 못했습니다."

version="$(aws_cli_version)"
[[ ${version} == aws-cli/2.* ]] \
    || fail "AWS CLI v2 설치를 확인하지 못했습니다: ${version}"

note "AWS CLI v2 설치 완료: ${version}"
