#!/usr/bin/env bash
#
# 배포 서버의 네트워크 경계를 만듭니다. 보안 그룹과 고정 공인 IP 입니다.
#
# 콘솔에서 클릭으로 만들면 무엇을 왜 열었는지가 남지 않습니다. 다음에 서버를 다시 만들 때
# 규칙 하나를 빠뜨렸는지 넓게 열었는지 확인할 방법도 없습니다.
#
# 여러 번 실행해도 결과가 같습니다. 이미 있으면 만들지 않고 ID 만 알려 줍니다.
#
# 사용:
#   SSH_ALLOWED_CIDR=203.0.113.10/32 bash deploy/aws/network.sh
#
# 필요한 권한: ec2:CreateSecurityGroup, ec2:AuthorizeSecurityGroupIngress,
#              ec2:AllocateAddress, ec2:Describe*

set -euo pipefail

AWS_REGION="${AWS_REGION:-ap-northeast-2}"
NAME="${NAME:-sb11-mopl-team1}"

# SSH 를 열어 줄 범위입니다. 기본값을 두지 않습니다. 0.0.0.0/0 이 기본이면 급할 때마다
# 그대로 쓰게 되고, 인증이 없는 포트가 아니라도 공인 IP 의 22 번은 하루 종일 두드려집니다.
SSH_ALLOWED_CIDR="${SSH_ALLOWED_CIDR:-}"

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
fail() { printf '\n\033[31m!! %s\033[0m\n' "$*" >&2; exit 1; }

[[ -n ${SSH_ALLOWED_CIDR} ]] || fail "SSH_ALLOWED_CIDR 이 필요합니다.
   지금 쓰는 주소: SSH_ALLOWED_CIDR=\$(curl -s https://checkip.amazonaws.com)/32"

[[ ${SSH_ALLOWED_CIDR} == */32 ]] || note "경고: ${SSH_ALLOWED_CIDR} 은 /32 가 아닙니다. 범위를 확인하세요."

aws() { command aws --region "${AWS_REGION}" "$@"; }

# ── VPC ────────────────────────────────────────────────────────────────────
log "VPC"
VPC_ID=$(aws ec2 describe-vpcs --filters Name=isDefault,Values=true \
    --query 'Vpcs[0].VpcId' --output text)
[[ ${VPC_ID} != "None" ]] || fail "기본 VPC 가 없습니다. VPC 를 먼저 정하세요."
note "${VPC_ID} (기본 VPC)"

# ── 보안 그룹 ──────────────────────────────────────────────────────────────
log "보안 그룹 ${NAME}"
SG_ID=$(aws ec2 describe-security-groups \
    --filters Name=group-name,Values="${NAME}" Name=vpc-id,Values="${VPC_ID}" \
    --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo "None")

if [[ ${SG_ID} == "None" || -z ${SG_ID} ]]; then
    SG_ID=$(aws ec2 create-security-group \
        --group-name "${NAME}" \
        --description "MOPL production host: 80/443 public, SSH restricted" \
        --vpc-id "${VPC_ID}" \
        --query 'GroupId' --output text)
    note "만들었습니다: ${SG_ID}"
else
    note "이미 있습니다: ${SG_ID}"
fi

# 규칙을 하나씩 넣습니다. 이미 있으면 InvalidPermission.Duplicate 가 나는데, 그것은
# 원하는 상태가 이미 맞다는 뜻이므로 넘어갑니다.
allow() {
    local port=$1 cidr=$2 desc=$3
    if aws ec2 authorize-security-group-ingress \
        --group-id "${SG_ID}" \
        --ip-permissions "IpProtocol=tcp,FromPort=${port},ToPort=${port},IpRanges=[{CidrIp=${cidr},Description='${desc}'}]" \
        >/dev/null 2>&1; then
        note "허용: ${port}/tcp ← ${cidr}  (${desc})"
    else
        note "이미 있음: ${port}/tcp ← ${cidr}"
    fi
}

# 외부에 여는 것은 이 둘뿐입니다. 80 은 HTTPS 전환과 ACME 인증서 발급에 필요합니다.
allow 80  0.0.0.0/0            "HTTP - redirects to HTTPS, ACME challenge"
allow 443 0.0.0.0/0            "HTTPS"
allow 22  "${SSH_ALLOWED_CIDR}" "SSH - admin only"

# PostgreSQL, Redis, Kafka, Elasticsearch 규칙은 넣지 않습니다. Compose 가 호스트 포트를
# 열지 않으므로 규칙이 없으면 닿을 수 없습니다. Redis 와 Kafka 에는 인증이 없어, 한 번
# 열리면 그 순간 그대로 공개됩니다.

log "현재 인바운드 규칙"
aws ec2 describe-security-groups --group-ids "${SG_ID}" \
    --query 'SecurityGroups[0].IpPermissions[].{port:FromPort,cidr:IpRanges[0].CidrIp,desc:IpRanges[0].Description}' \
    --output table | sed 's/^/   /'

# ── 고정 공인 IP ───────────────────────────────────────────────────────────
# 인스턴스를 멈췄다 켜면 공인 IP 가 바뀝니다. 도메인 A 레코드를 매번 고치게 되고, 그동안
# 인증서 갱신도 실패합니다.
log "Elastic IP ${NAME}"
EIP=$(aws ec2 describe-addresses --filters Name=tag:Name,Values="${NAME}" \
    --query 'Addresses[0].PublicIp' --output text 2>/dev/null || echo "None")

if [[ ${EIP} == "None" || -z ${EIP} ]]; then
    ALLOC_ID=$(aws ec2 allocate-address --domain vpc \
        --tag-specifications "ResourceType=elastic-ip,Tags=[{Key=Name,Value=${NAME}}]" \
        --query 'AllocationId' --output text)
    EIP=$(aws ec2 describe-addresses --allocation-ids "${ALLOC_ID}" \
        --query 'Addresses[0].PublicIp' --output text)
    note "할당했습니다: ${EIP}"
else
    ALLOC_ID=$(aws ec2 describe-addresses --filters Name=tag:Name,Values="${NAME}" \
        --query 'Addresses[0].AllocationId' --output text)
    note "이미 있습니다: ${EIP}"
fi

log "완료"
cat <<EOF
   보안 그룹   ${SG_ID}
   Elastic IP  ${EIP} (${ALLOC_ID})

   공인 IPv4 주소는 인스턴스에 붙어 있든 아니든 시간당 요금이 붙습니다. 쓰지 않게 되면
   release 하세요.

   다음: 이 보안 그룹과 IAM 인스턴스 프로파일 ${NAME}-ec2 를 붙여 인스턴스를 만들고,
   도메인 A 레코드를 ${EIP} 로 지정합니다.
EOF
