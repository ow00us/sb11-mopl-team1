#!/usr/bin/env bash
#
# GitHub Actions 가 ECR 에 이미지를 올릴 때 맡을 IAM 역할의 신뢰 정책을 만듭니다.
#
# 계정 ID 와 저장소 ID 를 파일에 적어 두지 않고 실행 시점에 조회합니다. 적어 두면 다른
# 계정이나 포크에서 그대로 쓸 수 없고, 틀린 값을 넣어도 적용할 때까지 드러나지 않습니다.
#
# 사용:
#   bash deploy/aws/github-oidc-role.sh            # 정책을 만들어 보여주기만 합니다
#   APPLY=true bash deploy/aws/github-oidc-role.sh # 역할에 실제로 적용합니다
#
# 필요한 것: aws CLI 자격 증명, gh CLI 로그인

set -euo pipefail

ROLE_NAME="${ROLE_NAME:-sb11-mopl-team1-github-actions}"
GITHUB_REPO="${GITHUB_REPO:-ow00us/sb11-mopl-team1}"
DEPLOY_REF="${DEPLOY_REF:-refs/heads/main}"

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
fail() { printf '\n\033[31m!! %s\033[0m\n' "$*" >&2; exit 1; }

command -v aws >/dev/null || fail "aws CLI 가 필요합니다."
command -v gh  >/dev/null || fail "gh CLI 가 필요합니다."

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"

# GitHub 은 OIDC subject 를 두 형태로 낼 수 있습니다.
#
#   repo:OWNER/REPO:ref:refs/heads/main
#   repo:OWNER@OWNER_ID/REPO@REPO_ID:ref:refs/heads/main   (불변 ID 형식)
#
# 어느 쪽이 오는지는 저장소 설정에 달려 있고, 한쪽만 넣어 두면 다른 쪽이 왔을 때
# AssumeRoleWithWebIdentity 가 거부됩니다. 그 사실은 배포가 처음 돌 때에야 드러납니다.
# 둘 다 받습니다. 불변 ID 형식이 더 안전한데, 저장소 이름이 바뀌어도 다른 저장소가
# 이 역할을 가로챌 수 없기 때문입니다.
OWNER="${GITHUB_REPO%%/*}"
REPO="${GITHUB_REPO##*/}"
OWNER_ID="$(gh api "users/${OWNER}" --jq .id)"
REPO_ID="$(gh api "repos/${GITHUB_REPO}" --jq .id)"

SUB_PLAIN="repo:${GITHUB_REPO}:ref:${DEPLOY_REF}"
SUB_IMMUTABLE="repo:${OWNER}@${OWNER_ID}/${REPO}@${REPO_ID}:ref:${DEPLOY_REF}"

log "확인한 값"
note "계정          ${ACCOUNT_ID}"
note "역할          ${ROLE_NAME}"
note "허용할 subject"
note "  ${SUB_PLAIN}"
note "  ${SUB_IMMUTABLE}"

# 저장소가 지금 어느 형식을 쓰는지 알려 줍니다. 맞지 않으면 여기서 드러납니다.
CURRENT_PREFIX="$(gh api "repos/${GITHUB_REPO}/actions/oidc/customization/sub" --jq .sub_claim_prefix 2>/dev/null || true)"
[[ -n ${CURRENT_PREFIX} ]] && note "저장소가 보고하는 prefix: ${CURRENT_PREFIX}"

TRUST_POLICY="$(cat <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          "token.actions.githubusercontent.com:sub": [
            "${SUB_IMMUTABLE}",
            "${SUB_PLAIN}"
          ]
        }
      }
    }
  ]
}
EOF
)"

log "신뢰 정책"
printf '%s\n' "${TRUST_POLICY}" | sed 's/^/   /'

if [[ ${APPLY:-false} != "true" ]]; then
    cat <<EOF

   적용하지 않았습니다. 실제로 반영하려면 APPLY=true 로 다시 실행하세요.

     APPLY=true bash deploy/aws/github-oidc-role.sh
EOF
    exit 0
fi

log "적용"
TMP="$(mktemp)"
trap 'rm -f "${TMP}"' EXIT
printf '%s\n' "${TRUST_POLICY}" > "${TMP}"

aws iam update-assume-role-policy \
    --role-name "${ROLE_NAME}" \
    --policy-document "file://${TMP}"
note "적용했습니다."

log "적용 결과"
aws iam get-role --role-name "${ROLE_NAME}" \
    --query 'Role.AssumeRolePolicyDocument.Statement[0].Condition' --output json \
    | sed 's/^/   /'
