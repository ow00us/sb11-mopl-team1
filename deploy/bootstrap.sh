#!/usr/bin/env bash
#
# 빈 Ubuntu 24.04 LTS 서버를 MOPL 운영 Compose 가 돌 수 있는 상태로 만듭니다.
#
# 콘솔에서 한 번 만지고 끝내지 않기 위한 스크립트입니다. 서버를 다시 만들 일이 생겼을 때
# 무엇을 했는지 기억에 의존하면, 빠뜨린 한 줄이 그날의 장애가 됩니다.
#
# 여러 번 실행해도 결과가 같습니다. 이미 있는 것은 건너뛰고, 바뀐 것만 맞춥니다.
#
# 사용:
#   sudo SSH_ALLOWED_CIDR=203.0.113.10/32 bash deploy/bootstrap.sh
#
# 이 스크립트가 하지 않는 것:
#   - 애플리케이션 기동. 이미지 태그와 Secret 이 정해진 뒤의 일입니다
#   - Secret 값 채우기. /etc/mopl/prod.env 는 빈 파일로만 만듭니다
#   - 도메인과 인증서. Caddy 가 기동 시 자동으로 받습니다

set -euo pipefail

DEPLOY_USER="${DEPLOY_USER:-deploy}"
MOPL_DATA_ROOT="${MOPL_DATA_ROOT:-/srv/mopl/data}"
MOPL_CONFIG_DIR="${MOPL_CONFIG_DIR:-/etc/mopl}"
MOPL_APP_DIR="${MOPL_APP_DIR:-/srv/mopl/app}"

# SSH 를 열어 줄 범위입니다. 지정하지 않으면 SSH 규칙을 건드리지 않습니다. 여기서 기본값을
# 0.0.0.0/0 으로 두면 "일단 되게" 하려는 순간마다 전 세계에 열리게 됩니다.
SSH_ALLOWED_CIDR="${SSH_ALLOWED_CIDR:-}"

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
fail() { printf '\n\033[31m!! %s\033[0m\n' "$*" >&2; exit 1; }

[[ ${EUID} -eq 0 ]] || fail "root 로 실행해야 합니다. sudo 를 붙이세요."

# SSH 규칙을 먼저 확인합니다. 기본 정책이 deny 인 방화벽을 SSH 허용 없이 켜면 지금 붙어
# 있는 접속까지 끊기고, 다시 들어갈 방법이 콘솔밖에 남지 않습니다. 디렉터리를 만든 뒤에
# 알게 되면 이미 늦습니다.
if [[ -z ${SSH_ALLOWED_CIDR} ]] \
    && ! (command -v ufw >/dev/null 2>&1 && ufw status | grep -q '22/tcp'); then
    fail "SSH_ALLOWED_CIDR 이 필요합니다. 없이 방화벽을 켜면 SSH 접속을 잃습니다.
   예: sudo SSH_ALLOWED_CIDR=\$(curl -s https://checkip.amazonaws.com)/32 bash \$0"
fi

# ── 배포 전용 사용자 ────────────────────────────────────────────────────────
# 애플리케이션을 root 로 돌리지 않기 위해서입니다. 컨테이너가 뚫렸을 때 호스트에서 할 수
# 있는 일을 줄입니다.
log "배포 사용자 ${DEPLOY_USER}"
if id -u "${DEPLOY_USER}" >/dev/null 2>&1; then
    note "이미 있습니다."
else
    # --disabled-password: 비밀번호 로그인을 막습니다. 접속은 SSH 키로만 합니다.
    adduser --system --group --shell /bin/bash --disabled-password \
        --home "/home/${DEPLOY_USER}" "${DEPLOY_USER}"
    note "만들었습니다."
fi

# ── Docker Engine ──────────────────────────────────────────────────────────
# 배포판 저장소의 docker.io 가 아니라 Docker 공식 저장소를 씁니다. compose v2 플러그인이
# 필요한데 배포판 패키지에는 들어 있지 않습니다.
log "Docker Engine"
if command -v docker >/dev/null 2>&1; then
    note "$(docker --version)"
else
    apt-get update -qq
    apt-get install -y -qq ca-certificates curl gnupg

    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
        -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc

    cat > /etc/apt/sources.list.d/docker.list <<EOF
deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${VERSION_CODENAME}") stable
EOF

    apt-get update -qq
    apt-get install -y -qq \
        docker-ce docker-ce-cli containerd.io \
        docker-buildx-plugin docker-compose-plugin
    note "$(docker --version)"
fi

systemctl enable --now docker >/dev/null
# docker 그룹은 사실상 root 권한입니다. 배포 사용자에게만 줍니다.
usermod -aG docker "${DEPLOY_USER}"

# ── 컨테이너 로그 한도 ──────────────────────────────────────────────────────
# 기본 json-file 드라이버는 한도가 없습니다. 그대로 두면 로그가 디스크를 채우고, 그때는
# 애플리케이션이 아니라 서버 전체가 멈춥니다. 무엇이 원인인지도 로그를 못 써서 안 남습니다.
log "컨테이너 로그 한도"
DOCKER_DAEMON_JSON=/etc/docker/daemon.json
DESIRED_DAEMON_JSON='{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "5"
  }
}'
if [[ -f ${DOCKER_DAEMON_JSON} ]] \
    && diff -q <(echo "${DESIRED_DAEMON_JSON}") "${DOCKER_DAEMON_JSON}" >/dev/null 2>&1; then
    note "이미 맞습니다."
else
    [[ -f ${DOCKER_DAEMON_JSON} ]] && cp -a "${DOCKER_DAEMON_JSON}" "${DOCKER_DAEMON_JSON}.bak"
    echo "${DESIRED_DAEMON_JSON}" > "${DOCKER_DAEMON_JSON}"
    systemctl restart docker
    note "서비스당 최대 250MB (50m x 5) 로 제한했습니다."
fi

# ── 디렉터리 ───────────────────────────────────────────────────────────────
log "디렉터리"

# 설정은 배포 사용자만 읽습니다. Secret 이 여기 있습니다.
install -d -o root -g "${DEPLOY_USER}" -m 0750 "${MOPL_CONFIG_DIR}"
install -d -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" -m 0755 "${MOPL_APP_DIR}"

# 환경 파일은 없을 때만 만듭니다. 있으면 채워 둔 값을 지우게 됩니다.
if [[ -e ${MOPL_CONFIG_DIR}/prod.env ]]; then
    note "${MOPL_CONFIG_DIR}/prod.env 가 이미 있습니다. 그대로 둡니다."
else
    install -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" -m 0600 \
        /dev/null "${MOPL_CONFIG_DIR}/prod.env"
    note "${MOPL_CONFIG_DIR}/prod.env 를 빈 파일로 만들었습니다. 값은 직접 채웁니다."
fi

# 배포 기록 파일을 미리 만듭니다. ${MOPL_CONFIG_DIR} 는 group 에 쓰기 권한이 없어
# deploy 가 새 파일을 만들 수 없습니다. 파일이 있으면 내용만 바꿀 수 있습니다. 배포
# 스크립트에 sudo 를 주지 않기 위한 것입니다.
if [[ -e ${MOPL_CONFIG_DIR}/deploy-state.env ]]; then
    note "${MOPL_CONFIG_DIR}/deploy-state.env 가 이미 있습니다. 그대로 둡니다."
else
    install -o "${DEPLOY_USER}" -g "${DEPLOY_USER}" -m 0640 \
        /dev/null "${MOPL_CONFIG_DIR}/deploy-state.env"
    note "${MOPL_CONFIG_DIR}/deploy-state.env 를 만들었습니다."
fi

# 데이터 디렉터리의 소유자를 이미지의 실행 사용자에 맞춥니다.
#
# bind mount 는 named volume 과 달리 Docker 가 소유자를 고쳐 주지 않습니다. postgres 와
# redis 는 entrypoint 가 root 로 시작해 스스로 chown 하지만, elasticsearch 와 kafka 는
# 이미지가 USER 를 지정해 두어 처음부터 비특권 사용자로 실행됩니다. 소유자가 맞지 않으면
# 기동하지 못합니다.
#
# UID 를 적어 두지 않고 이미지에서 직접 읽습니다. 이미지가 올라가면서 UID 가 바뀌면 적어
# 둔 값은 조용히 틀리는데, 그 사실은 다음 서버를 만들 때에야 드러납니다.
#
# 받을 수 없는 이미지는 아래 기본값을 씁니다. Elasticsearch 는 nori 플러그인을 넣어 직접
# 만든 이미지라 ECR 로그인 전에는 받지 못합니다. prod.env 를 채운 뒤 이 스크립트를 다시
# 실행하면 실제 이미지에서 읽습니다. 여러 번 실행해도 되도록 만든 이유가 이것입니다.
if [[ -r ${MOPL_CONFIG_DIR}/prod.env ]]; then
    ELASTICSEARCH_IMAGE=$(sed -n 's/^ELASTICSEARCH_IMAGE=//p' "${MOPL_CONFIG_DIR}/prod.env")
fi

image_uid() {
    local image=$1 fallback=$2 uid
    if [[ -n ${image} ]] \
        && uid=$(docker run --rm --entrypoint id "${image}" -u 2>/dev/null) \
        && [[ ${uid} =~ ^[0-9]+$ ]]; then
        echo "${uid}"
    else
        note "${image:-이미지 미지정} 에서 UID 를 읽지 못했습니다. ${fallback} 으로 둡니다." >&2
        echo "${fallback}"
    fi
}

install -d -m 0755 "${MOPL_DATA_ROOT}"

# 이름|이미지|UID 기본값. 기본값은 각 이미지의 현재 실행 사용자이고, Elasticsearch 는
# docker/elasticsearch/Dockerfile 의 베이스 이미지를 따릅니다.
while IFS='|' read -r name image fallback; do
    [[ -n ${name} ]] || continue
    uid=$(image_uid "${image}" "${fallback}")
    install -d -o "${uid}" -g "${uid}" -m 0700 "${MOPL_DATA_ROOT}/${name}"
    note "${MOPL_DATA_ROOT}/${name} → uid ${uid}"
done <<EOF
postgres|postgres:16|999
redis|redis:7|999
kafka|apache/kafka:3.8.0|1000
elasticsearch|${ELASTICSEARCH_IMAGE:-}|1000
EOF

# ── 방화벽 ─────────────────────────────────────────────────────────────────
# 보안 그룹이 1차 방어입니다. 여기는 2차입니다. 보안 그룹을 누가 넓게 고쳐도 호스트에서
# 한 번 더 막힙니다.
#
# 데이터 서비스 포트는 규칙을 따로 두지 않습니다. Compose 가 호스트에 열지 않으므로 열릴
# 포트 자체가 없고, 기본 정책이 deny 라 실수로 열려도 여기서 막힙니다.
log "방화벽"
apt-get install -y -qq ufw
ufw --force default deny incoming >/dev/null
ufw --force default allow outgoing >/dev/null
ufw allow 80/tcp  comment 'HTTP (Caddy, ACME 및 HTTPS 전환)' >/dev/null
ufw allow 443/tcp comment 'HTTPS (Caddy)' >/dev/null
note "80, 443 을 열었습니다."

if [[ -n ${SSH_ALLOWED_CIDR} ]]; then
    # 기존 SSH 규칙을 지우고 다시 씁니다. 예전 범위가 남아 있으면 좁힌 것이 아닙니다.
    while ufw status numbered | grep -q '22/tcp'; do
        num=$(ufw status numbered | grep -m1 '22/tcp' | sed 's/^\[\s*\([0-9]*\)\].*/\1/')
        ufw --force delete "${num}" >/dev/null
    done
    ufw allow from "${SSH_ALLOWED_CIDR}" to any port 22 proto tcp \
        comment 'SSH (관리 경로)' >/dev/null
    note "SSH 를 ${SSH_ALLOWED_CIDR} 에서만 허용합니다."
else
    note "SSH_ALLOWED_CIDR 이 없어 SSH 규칙을 건드리지 않았습니다."
    note "지금 접속을 잃지 않으려면 이 값을 주고 다시 실행하세요."
fi

ufw --force enable >/dev/null
ufw status verbose | sed 's/^/   /'

# ── 마무리 ─────────────────────────────────────────────────────────────────
log "완료"
cat <<EOF
   다음 순서로 진행합니다.

   1. ${MOPL_CONFIG_DIR}/prod.env 를 .env.example 기준으로 채웁니다
   2. docker-compose.prod.yml 과 deploy/Caddyfile 을 ${MOPL_APP_DIR} 에 둡니다
   3. ECR 로그인 후 이미지를 받습니다
   4. sudo -u ${DEPLOY_USER} docker compose -f ${MOPL_APP_DIR}/docker-compose.prod.yml \\
        --env-file ${MOPL_CONFIG_DIR}/prod.env up -d

   자세한 절차는 DEPLOYMENT.md 의 "배포 서버 준비" 절에 있습니다.
EOF
