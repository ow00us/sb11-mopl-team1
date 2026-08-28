#!/bin/bash
set -euo pipefail

CONTENT_ID="${CONTENT_ID:?CONTENT_ID를 export 하세요}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-300}"
PEAK_VUS="${PEAK_VUS:-300}"
RUNS="${RUNS:-3}"
CONDITIONS="${CONDITIONS:-ABC}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
WS_URL="${WS_URL:-ws://localhost:8080}"
K6_BIN="${K6_BIN:-k6}"
PERF_ENV_FILE="${PERF_ENV_FILE:-./perf.env}"
RAW_JSON="${RAW_JSON:-false}"
METRICS_EMAIL="${METRICS_EMAIL:-k6-watch-0@loadtest.local}"
METRICS_PASSWORD="${METRICS_PASSWORD:-Load1234!test}"
MEASUREMENT_ID="${MEASUREMENT_ID:-$(date -u +%Y%m%dT%H%M%SZ)-$$}"
MANIFEST_PATH="results/measurement-manifest.txt"
BACKEND_IMAGE_ID=""

if [[ ! "$CONDITIONS" =~ ^[ABC]+$ ]]; then
  echo "CONDITIONS에는 A, B, C만 사용할 수 있습니다." >&2
  exit 1
fi

normalized_conditions=""
for condition in A B C; do
  if [[ "$CONDITIONS" == *"$condition"* ]]; then
    normalized_conditions+="$condition"
  fi
done
CONDITIONS="$normalized_conditions"

if [[ -z "$CONDITIONS" ]]; then
  echo "CONDITIONS에는 A, B, C 중 하나 이상이 필요합니다." >&2
  exit 1
fi
if [[ ! "$RUNS" =~ ^[1-9][0-9]*$ ]]; then
  echo "RUNS는 1 이상의 정수여야 합니다." >&2
  exit 1
fi
if [[ ! "$ACCOUNT_COUNT" =~ ^[1-9][0-9]*$ ]] \
  || [[ ! "$PEAK_VUS" =~ ^[1-9][0-9]*$ ]]; then
  echo "ACCOUNT_COUNT와 PEAK_VUS는 1 이상의 정수여야 합니다." >&2
  exit 1
fi
if ((ACCOUNT_COUNT < PEAK_VUS || ACCOUNT_COUNT < 20)); then
  echo "ACCOUNT_COUNT는 PEAK_VUS와 warmup VU(20) 이상이어야 합니다." >&2
  exit 1
fi
if [[ ! "$MEASUREMENT_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "MEASUREMENT_ID는 영문, 숫자, 점, 밑줄, 하이픈만 사용할 수 있습니다." >&2
  exit 1
fi

DC=(
  docker compose
  -f docker-compose.prod.yml
  -f docker-compose.perf-override.yml
  --env-file "$PERF_ENV_FILE"
)

mkdir -p results

# ── 헬퍼 ──────────────────────────────────────────────
condition_enabled() {
  [[ "$CONDITIONS" == *"$1"* ]]
}

clear_previous_results() {
  (
    shopt -s nullglob
    local files=(
      results/A-1inst-cache-on-run*.json
      results/A-1inst-cache-on-run*-config-backend-*.txt
      results/A-1inst-cache-on-run*-metrics-backend-*.txt
      results/B-1inst-cache-off-run*.json
      results/B-1inst-cache-off-run*-config-backend-*.txt
      results/B-1inst-cache-off-run*-metrics-backend-*.txt
      results/C-2inst-cache-on-run*.json
      results/C-2inst-cache-on-run*-config-backend-*.txt
      results/C-2inst-cache-on-run*-metrics-backend-*.txt
      "$MANIFEST_PATH"
    )
    if ((${#files[@]} > 0)); then
      rm -f -- "${files[@]}"
    fi
  )
}

file_sha256() {
  sha256sum "$1" | awk '{print $1}'
}

service_image_id() {
  local container_id
  container_id="$("${DC[@]}" ps -q "$1")"
  if [[ -z "$container_id" ]]; then
    echo "$1 컨테이너를 찾지 못했습니다." >&2
    return 1
  fi
  docker inspect --format '{{.Image}}' "$container_id"
}

initialize_measurement_set() {
  local gateway_image_id postgres_id postgres_data_source
  local backend_git_sha frontend_git_sha k6_version docker_runtime

  clear_previous_results

  BACKEND_IMAGE_ID="$(service_image_id backend-a)"
  gateway_image_id="$(service_image_id gateway)"
  postgres_id="$("${DC[@]}" ps -q postgres)"
  postgres_data_source="$(docker inspect --format \
    '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Source}}{{end}}{{end}}' \
    "$postgres_id")"
  backend_git_sha="$(awk -F': ' '$1 == "backend" {print $2}' results/GIT_SHA.txt | awk '{print $1}')"
  frontend_git_sha="$(awk -F': ' '$1 == "frontend" {print $2}' results/GIT_SHA.txt | awk '{print $1}')"
  k6_version="$("$K6_BIN" version | tr -d '\r' | head -n 1)"
  docker_runtime="$(docker info --format '{{.OSType}}/{{.Architecture}} cpu={{.NCPU}} memory={{.MemTotal}}')"

  {
    printf 'measurement_id=%s\n' "$MEASUREMENT_ID"
    printf 'started_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'conditions=%s\n' "$CONDITIONS"
    printf 'runs=%s\n' "$RUNS"
    printf 'content_id=%s\n' "$CONTENT_ID"
    printf 'account_count=%s\n' "$ACCOUNT_COUNT"
    printf 'peak_vus=%s\n' "$PEAK_VUS"
    printf 'base_url=%s\n' "$BASE_URL"
    printf 'ws_url=%s\n' "$WS_URL"
    printf 'raw_json=%s\n' "$RAW_JSON"
    printf 'backend_git_sha=%s\n' "$backend_git_sha"
    printf 'frontend_git_sha=%s\n' "$frontend_git_sha"
    printf 'backend_image_id=%s\n' "$BACKEND_IMAGE_ID"
    printf 'gateway_image_id=%s\n' "$gateway_image_id"
    printf 'postgres_data_source=%s\n' "$postgres_data_source"
    printf 'docker_runtime=%s\n' "$docker_runtime"
    printf 'k6_version=%s\n' "$k6_version"
    printf 'run_all_sha256=%s\n' "$(file_sha256 run_all.sh)"
    printf 'collect_results_sha256=%s\n' "$(file_sha256 collect_results.sh)"
    printf 'compose_override_sha256=%s\n' "$(file_sha256 docker-compose.perf-override.yml)"
    printf 'scenario_sha256=%s\n' "$(file_sha256 k6/watch-chat-spike.js)"
    printf 'auth_helper_sha256=%s\n' "$(file_sha256 k6/lib/auth.js)"
    printf 'stomp_helper_sha256=%s\n' "$(file_sha256 k6/lib/stomp.js)"
  } > "$MANIFEST_PATH"
}

complete_measurement_set() {
  printf 'status=complete\nfinished_at_utc=%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$MANIFEST_PATH"
}

get_metrics_token() {
  local cookie_file response_file csrf_status sign_in_status xsrf_token token
  cookie_file="$(mktemp)"
  response_file="$(mktemp)"

  csrf_status="$(
    curl --silent --show-error \
      --output /dev/null \
      --write-out '%{http_code}' \
      --cookie-jar "$cookie_file" \
      "$BASE_URL/api/auth/csrf-token"
  )"

  if [[ "$csrf_status" != "204" ]]; then
    rm -f "$cookie_file" "$response_file"
    echo "Prometheus 인증용 CSRF 요청 실패: HTTP $csrf_status" >&2
    return 1
  fi

  xsrf_token="$(
    awk '$6 == "XSRF-TOKEN" { value = $7 } END { print value }' \
      "$cookie_file"
  )"

  if [[ -z "$xsrf_token" ]]; then
    rm -f "$cookie_file" "$response_file"
    echo "Prometheus 인증용 XSRF 토큰을 얻지 못했습니다." >&2
    return 1
  fi

  sign_in_status="$(
    curl --silent --show-error \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --cookie "$cookie_file" \
      --header 'Content-Type: application/json' \
      --header "X-XSRF-TOKEN: $xsrf_token" \
      --data "{\"email\":\"$METRICS_EMAIL\",\"password\":\"$METRICS_PASSWORD\"}" \
      "$BASE_URL/api/auth/sign-in"
  )"

  if [[ "$sign_in_status" != "200" ]]; then
    rm -f "$cookie_file" "$response_file"
    echo "Prometheus 인증용 로그인 실패: HTTP $sign_in_status" >&2
    return 1
  fi

  token="$(
    sed -n 's/.*"accessToken"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
      "$response_file"
  )"

  rm -f "$cookie_file" "$response_file"

  if [[ -z "$token" ]]; then
    echo "Prometheus 인증용 access token을 얻지 못했습니다." >&2
    return 1
  fi

  printf '%s' "$token"
}

backend_metrics_url() {
  case "$1" in
    backend-a) printf '%s' 'http://localhost:18080' ;;
    backend-b) printf '%s' 'http://localhost:18081' ;;
    *) echo "알 수 없는 백엔드: $1" >&2; return 1 ;;
  esac
}

snapshot_metrics() {   # $1: 결과 접두사, $2: before|after, $3: token, $4...: 인스턴스
  local prefix="$1"
  local phase="$2"
  local token="$3"
  shift 3

  for svc in "$@"; do
    local metrics_url output_file
    metrics_url="$(backend_metrics_url "$svc")/actuator/prometheus"
    output_file="results/${prefix}-metrics-${svc}-${phase}.txt"

    curl --silent --show-error --fail \
      --header "Authorization: Bearer $token" \
      "$metrics_url" \
      | grep -E \
          '^(process_start_time_seconds|hikaricp_|mopl_watchingsession_ratelimit_dropped_total|mopl_realtime_relay_|spring_websocket_)' \
      > "$output_file"

    if ! grep -q '^mopl_watchingsession_ratelimit_dropped_total' "$output_file"; then
      echo "$svc의 드롭 메트릭을 수집하지 못했습니다." >&2
      return 1
    fi
  done
}

snapshot_runtime_config() {   # $1: 결과 접두사, $2...: 인스턴스
  local prefix="$1"
  shift

  for svc in "$@"; do
    "${DC[@]}" exec -T "$svc" \
      printenv WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL \
      > "results/${prefix}-config-${svc}.txt"
  done
}

reset_redis() {
  "${DC[@]}" exec -T redis sh -eu -c \
    'redis-cli ping >/dev/null
     keys_file="$(mktemp)"
     for pattern in \
       "mopl:presence:*" \
       "mopl:chat:buffer:*" \
       "mopl:content:exists:*"
     do
       redis-cli --scan --pattern "$pattern" > "$keys_file"
       if test -s "$keys_file"; then
         xargs -r redis-cli DEL < "$keys_file" >/dev/null
       fi
       redis-cli --scan --pattern "$pattern" > "$keys_file"
       test ! -s "$keys_file"
     done
     rm -f "$keys_file"' >/dev/null
}

wait_healthy() {   # $1: 서비스명
  local container_id health
  echo "  $1 healthy 대기..."
  for _ in $(seq 1 60); do
    container_id="$("${DC[@]}" ps -q "$1")"
    if [[ -n "$container_id" ]] \
      && health="$(docker inspect --format '{{.State.Health.Status}}' "$container_id" 2>/dev/null)" \
      && [[ "$health" == "healthy" ]]; then
      if [[ "$1" == backend-* ]]; then
        local actual_image_id
        actual_image_id="$(docker inspect --format '{{.Image}}' "$container_id")"
        if [[ "$actual_image_id" != "$BACKEND_IMAGE_ID" ]]; then
          echo "$1 이미지가 manifest와 다릅니다: $actual_image_id" >&2
          exit 1
        fi
      fi
      echo "  $1 준비됨"
      return 0
    fi
    sleep 5
  done
  echo "  $1 가 healthy 되지 않았습니다"
  exit 1
}

run_k6() {   # $1: 결과 파일 접두사
  local prefix="$1"
  local -a output_args=()

  if [[ "$RAW_JSON" == "true" ]]; then
    output_args=(--out "json=results/${prefix}.json")
  fi

  "$K6_BIN" run --quiet \
    --env BASE_URL="$BASE_URL" \
    --env WS_URL="$WS_URL" \
    --env CONTENT_ID="$CONTENT_ID" \
    --env ACCOUNT_COUNT="$ACCOUNT_COUNT" \
    --env PEAK_VUS="$PEAK_VUS" \
    --env MEASUREMENT_ID="$MEASUREMENT_ID" \
    --env SUMMARY_PATH="results/${prefix}-summary.json" \
    "${output_args[@]}" \
    k6/watch-chat-spike.js
}

run_measurement() {   # $1: 결과 접두사, $2...: 인스턴스
  local prefix="$1"
  shift
  local token

  rm -f \
    "results/${prefix}-summary.json" \
    "results/${prefix}.json"
  for svc in "$@"; do
    rm -f \
      "results/${prefix}-config-${svc}.txt" \
      "results/${prefix}-metrics-${svc}-before.txt" \
      "results/${prefix}-metrics-${svc}-after.txt" \
      "results/${prefix}-dropped-${svc}.txt"
  done

  reset_redis
  snapshot_runtime_config "$prefix" "$@"
  token="$(get_metrics_token)"
  snapshot_metrics "$prefix" before "$token" "$@"
  run_k6 "$prefix"
  if [[ ! -s "results/${prefix}-summary.json" ]]; then
    echo "${prefix} 요약 파일이 생성되지 않았습니다." >&2
    return 1
  fi
  snapshot_metrics "$prefix" after "$token" "$@"
}

if ! curl --silent --show-error --fail \
  "$BASE_URL/actuator/health/liveness" >/dev/null; then
  echo "gateway가 준비되지 않았습니다. README의 compose 기동 단계를 먼저 실행하세요." >&2
  exit 1
fi

initialize_measurement_set

# ── 조건 A: 1인스턴스 · 캐시 on ────────────────────────
if condition_enabled A; then
  echo "=== [A] 1인스턴스 · 캐시 on ==="
  export WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=30s
  "${DC[@]}" stop backend-b

  for i in $(seq 1 "$RUNS"); do
    echo "--- A run $i/$RUNS ---"
    "${DC[@]}" up -d --force-recreate backend-a
    wait_healthy backend-a
    sleep 15   # gateway resolver valid=10s 반영
    run_measurement "A-1inst-cache-on-run${i}" backend-a
    sleep 30
  done
fi

# ── 조건 B: 1인스턴스 · 캐시 TTL 1ms ───────────────────
if condition_enabled B; then
  echo "=== [B] 1인스턴스 · 캐시 TTL 1ms ==="
  export WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=1ms
  "${DC[@]}" stop backend-b

  for i in $(seq 1 "$RUNS"); do
    echo "--- B run $i/$RUNS ---"
    "${DC[@]}" up -d --force-recreate backend-a
    wait_healthy backend-a
    sleep 15
    run_measurement "B-1inst-cache-off-run${i}" backend-a
    sleep 30
  done
fi

# ── 조건 C: 2인스턴스 · 캐시 on ────────────────────────
if condition_enabled C; then
  echo "=== [C] 2인스턴스 · 캐시 on ==="
  export WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL=30s

  for i in $(seq 1 "$RUNS"); do
    echo "--- C run $i/$RUNS ---"
    "${DC[@]}" up -d --force-recreate backend-a backend-b
    wait_healthy backend-a
    wait_healthy backend-b
    sleep 15
    run_measurement "C-2inst-cache-on-run${i}" backend-a backend-b
    sleep 30
  done
fi

complete_measurement_set
echo "=== 선택한 측정 완료. results/ 확인 ==="
