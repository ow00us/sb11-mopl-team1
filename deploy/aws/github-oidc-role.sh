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
# 인자로 저장소를 주면 그것만 씁니다. 주지 않으면 백엔드와 프론트엔드 둘 다 넣습니다.
#
# 필요한 것: aws CLI 자격 증명, gh CLI 로그인

set -euo pipefail

ROLE_NAME="${ROLE_NAME:-sb11-mopl-team1-github-actions}"
DEPLOY_REF="${DEPLOY_REF:-refs/heads/main}"

# 백엔드와 프론트엔드가 같은 역할을 씁니다. 저장소마다 역할을 따로 두면 ECR 권한과
# 신뢰 조건이 두 벌이 되고, 한쪽만 고쳐 둔 채로 다른 쪽이 조용히 틀어집니다.
# 권한 범위는 역할에 붙은 정책이 리포지토리 단위로 제한합니다.
GITHUB_REPOS=("${@:-}")
if [[ -z ${GITHUB_REPOS[0]:-} ]]; then
    GITHUB_REPOS=(ow00us/sb11-mopl-team1 ow00us/sb11-mopl-team1-fe)
fi

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
log "확인한 값"
note "계정   ${ACCOUNT_ID}"
note "역할   ${ROLE_NAME}"

SUBS=()
for repo in "${GITHUB_REPOS[@]}"; do
    owner="${repo%%/*}"
    name="${repo##*/}"
    owner_id="$(gh api "users/${owner}" --jq .id)"
    repo_id="$(gh api "repos/${repo}" --jq .id)"

    SUBS+=("repo:${owner}@${owner_id}/${name}@${repo_id}:ref:${DEPLOY_REF}")
    SUBS+=("repo:${repo}:ref:${DEPLOY_REF}")

    note ""
    note "${repo}"
    note "  repo:${owner}@${owner_id}/${name}@${repo_id}:ref:${DEPLOY_REF}"
    note "  repo:${repo}:ref:${DEPLOY_REF}"

    # 저장소가 지금 어느 형식을 쓰는지 알려 줍니다. 맞지 않으면 여기서 드러납니다.
    prefix="$(gh api "repos/${repo}/actions/oidc/customization/sub" --jq .sub_claim_prefix 2>/dev/null || true)"
    [[ -n ${prefix} ]] && note "  저장소가 보고하는 prefix: ${prefix}"
done

# 마지막 항목의 쉼표를 뺍니다. JSON 은 trailing comma 를 허용하지 않습니다.
SUB_JSON="$(printf '            "%s",
' "${SUBS[@]}" | sed '$ s/,$//')"

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
${SUB_JSON}
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
