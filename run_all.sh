#!/bin/bash
set -euo pipefail

CONTENT_ID="${CONTENT_ID:?CONTENT_ID를 export 하세요}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-300}"
PEAK_VUS="${PEAK_VUS:-300}"
RUNS="${RUNS:-3}"

DC="docker compose -f docker-compose.prod.yml -f docker-compose.perf-override.yml --env-file ./perf.env"

mkdir -p results

# ── 헬퍼 ──────────────────────────────────────────────
snapshot_dropped() {   # $1: 결과 파일 접두사, $2: 인스턴스 목록
  local prefix="$1"; shift
  for svc in "$@"; do
    $DC exec -T "$svc" curl -s http://localhost:8080/actuator/prometheus \
      | grep ratelimit > "results/${prefix}-dropped-${svc}.txt" || true
  done
}

reset_redis() {
  $DC exec -T redis sh -c \
    'redis-cli --scan --pattern "mopl:presence:*" | xargs -r redis-cli DEL; \
     redis-cli --scan --pattern "mopl:content:chat:*" | xargs -r redis-cli DEL' >/dev/null || true
}

wait_healthy() {   # $1: 서비스명
  echo "  $1 healthy 대기..."
  for _ in $(seq 1 60); do
    if $DC ps "$1" | grep -q "healthy"; then echo "  $1 준비됨"; return 0; fi
    sleep 5
  done
  echo "  $1 가 healthy 되지 않았습니다"; exit 1
}

run_k6() {   # $1: 결과 파일 접두사
  local prefix="$1"
  k6 run \
    --env BASE_URL=http://localhost:8080 \
    --env WS_URL=ws://localhost:8080 \
    --env CONTENT_ID="$CONTENT_ID" \
    --env ACCOUNT_COUNT="$ACCOUNT_COUNT" \
    --env PEAK_VUS="$PEAK_VUS" \
    --out json="results/${prefix}.json" \
    --summary-export="results/${prefix}-summary.json" \
    k6/watch-chat-spike.js
}

# ── 조건 A: 1인스턴스 · 캐시 on ────────────────────────
echo "=== [A] 1인스턴스 · 캐시 on ==="
sed -i '' 's|^WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=.*|WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=30s|' perf.env
$DC stop backend-b
$DC up -d --force-recreate backend-a
wait_healthy backend-a
sleep 15   # Nginx resolver valid=10s 반영

for i in $(seq 1 "$RUNS"); do
  echo "--- A run $i/$RUNS ---"
  reset_redis
  run_k6 "A-1inst-cache-on-run${i}"
  snapshot_dropped "A-1inst-cache-on-run${i}" backend-a
  sleep 30
done

# ── 조건 B: 1인스턴스 · 캐시 TTL 1ms ───────────────────
echo "=== [B] 1인스턴스 · 캐시 TTL 1ms ==="
sed -i '' 's|^WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=.*|WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=1ms|' perf.env
$DC up -d --force-recreate backend-a
wait_healthy backend-a
sleep 15

for i in $(seq 1 "$RUNS"); do
  echo "--- B run $i/$RUNS ---"
  reset_redis
  run_k6 "B-1inst-cache-off-run${i}"
  snapshot_dropped "B-1inst-cache-off-run${i}" backend-a
  sleep 30
done

# ── 조건 C: 2인스턴스 · 캐시 on ────────────────────────
echo "=== [C] 2인스턴스 · 캐시 on ==="
sed -i '' 's|^WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=.*|WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=30s|' perf.env
$DC up -d --force-recreate backend-a backend-b
wait_healthy backend-a
wait_healthy backend-b
sleep 15

for i in $(seq 1 "$RUNS"); do
  echo "--- C run $i/$RUNS ---"
  reset_redis
  run_k6 "C-2inst-cache-on-run${i}"
  snapshot_dropped "C-2inst-cache-on-run${i}" backend-a backend-b
  sleep 30
done

echo "=== 전체 측정 완료. results/ 확인 ==="
