# 시청 세션·같이 보기 k6 부하 테스트

같은 콘텐츠에 동시 접속이 몰리는 상황에서 watch·chat STOMP 경로를 반복 측정한다. 최종 결과와 해석은 [`docs/perf/watching-session/README.md`](../docs/perf/watching-session/README.md)를 참조한다.

## 구성

```text
k6/
  lib/
    auth.js              CSRF → 로그인 → accessToken 획득
    stomp.js             STOMP 프레임 조립·header/body 파싱
  seed-users.js          측정용 계정 사전 생성
  watch-chat-spike.js    warmup·measure 시나리오
run_all.sh               조건·반복 실행, 환경 초기화, 증거 수집
collect_results.sh       manifest·summary·서버 메트릭 검증과 집계
docker-compose.perf-override.yml
                         로컬 측정용 포트·설정 override
```

## 사전 조건

- k6 v2 계열
- Docker Desktop와 `!override` 태그를 지원하는 Docker Compose v2.24.4 이상
- `docker compose`, `curl`, `awk`, `sha256sum`, Python 3를 사용할 수 있는 Bash 환경
- `docker-compose.prod.yml`과 `docker-compose.perf-override.yml`
- Docker Desktop에서 공유 가능한 전용 영속 데이터 경로

Windows에서는 Git Bash를 사용할 수 있다. `K6_BIN`으로 k6 실행 파일 절대 경로를 지정할 수 있다. macOS에서 300 VU를 실행할 때는 파일 디스크립터 한도를 확인한다.

```bash
ulimit -n 10240
```

용량 평가가 목적이라면 k6를 서버와 다른 호스트에 둔다. 같은 호스트에서 부하 생성기와 서버를 실행한 결과는 로컬 통합 재현이며 운영 환경의 절대 용량이 아니다.

## `perf.env` 준비

`.env.example`을 복사해 `perf.env`를 만들고 측정 전용 값을 채운다. `perf.env`는 Git에 커밋하지 않는다.

```bash
cp .env.example perf.env
```

| 변수 | 용도 |
|---|---|
| `MOPL_DOMAIN` | 로컬 측정은 `localhost` |
| `BACKEND_IMAGE`, `FRONTEND_IMAGE` | 측정 대상 이미지 태그 |
| `MOPL_DATA_ROOT` | Docker Desktop에서 공유 가능한 전용 절대 경로 |
| `JWT_SECRET`, `OAUTH2_LOCAL_CREDENTIAL_VERIFICATION_SECRET` | 측정 전용 비밀값 |
| `POSTGRES_*`, `MAIL_*`, OAuth 값 | 측정 환경 기동 값 |
| `ELASTICSEARCH_IMAGE` | nori 플러그인 포함 이미지 |

Windows에서는 `.env.example`의 Linux 경로를 그대로 쓰지 않는다. 계정과 콘텐츠를 시딩한 뒤에는 같은 `MOPL_DATA_ROOT`를 유지한다.

override는 Caddy·gateway·backend 진단 포트를 모두 `127.0.0.1`에만 바인딩하고, backend에 다음을 주입한다.

- `IMAGE_STORAGE_ENABLED=false`
- `KAFKA_TOPIC_VERIFY=false`
- `WATCHING_SESSION_CONTENT_EXISTENCE_CACHE_TTL`
    - A/C: 30s
    - B: 1ms

`run_all.sh`는 TTL을 현재 실행 프로세스 환경으로 compose에 전달한다. `perf.env` 파일을 수정하지 않는다.

## 네트워크 경로

- k6 HTTP/WS: gateway `localhost:8080`
- backend-a 진단: `localhost:18080`
- backend-b 진단: `localhost:18081`

k6는 반드시 gateway로 요청한다. Caddy·gateway·backend 포트는 모두 loopback으로만 노출하며 backend 포트는 인증된 Prometheus 수집에만 사용한다.

## 기동과 데이터 준비

```bash
docker compose \
  -f docker-compose.prod.yml \
  -f docker-compose.perf-override.yml \
  --env-file ./perf.env up -d

docker compose \
  -f docker-compose.prod.yml \
  -f docker-compose.perf-override.yml \
  --env-file ./perf.env ps
```

계정 300개를 시딩한다. 이미 존재하는 계정의 409 응답은 건너뛴다.

```bash
k6 run \
  --env BASE_URL=http://localhost:8080 \
  --env COUNT=300 \
  k6/seed-users.js
```

관리자 계정으로 측정 대상 콘텐츠 1개를 만들고 UUID를 확보한다. 모든 회차에서 같은 UUID를 사용한다.

## 측정 실행

```bash
export CONTENT_ID=<고정 콘텐츠 UUID>
export ACCOUNT_COUNT=300
export PEAK_VUS=300
export CONDITIONS=ABC
export RUNS=3

set -o pipefail
./run_all.sh 2>&1 | tee results/run_all.log
./collect_results.sh | tee results/summary_table.txt
```

| 변수 | 기본값 | 설명 |
|---|---:|---|
| `CONDITIONS` | `ABC` | 실행 조건. `C`, `AB`처럼 선택 가능 |
| `RUNS` | `3` | 조건별 반복 횟수 |
| `ACCOUNT_COUNT` | `300` | setup에서 확보할 계정 수 |
| `PEAK_VUS` | `300` | measure 최고 VU |
| `BASE_URL` | `http://localhost:8080` | HTTP gateway |
| `WS_URL` | `ws://localhost:8080` | WebSocket gateway |
| `K6_BIN` | `k6` | k6 실행 파일 |
| `PERF_ENV_FILE` | `./perf.env` | compose 환경 파일 |
| `RAW_JSON` | `false` | 대용량 raw k6 출력 생성 여부 |
| `METRICS_EMAIL`, `METRICS_PASSWORD` | 첫 시딩 계정 | Prometheus 인증 계정 |
| `MEASUREMENT_ID` | UTC 시각+PID | 동일 측정 세트 식별자 |

표준 측정은 `CONDITIONS=ABC`로 한 번에 실행한다. 분할 실행 파일을 수동 결합하지 않는다.

## 조건

1. A: backend 1개, 콘텐츠 존재 캐시 TTL 30s
2. B: backend 1개, 콘텐츠 존재 캐시 TTL 1ms
3. C: backend 2개, 콘텐츠 존재 캐시 TTL 30s

결과 파일의 기존 접두사 `B-1inst-cache-off`는 호환을 위해 유지했지만 실제 조건은 캐시 비활성화가 아니라 TTL 1ms다.

각 회차는 다음 순서로 실행된다.

1. 필요한 backend를 `--force-recreate`
2. 정확한 컨테이너 ID의 health 확인
3. backend 이미지 ID 일치 확인
4. gateway DNS 반영 대기
5. Redis `presence`, `chat:buffer`, `content:exists` key 초기화와 빈 상태 재확인
6. 실제 TTL과 인증된 Prometheus before 스냅샷 저장
7. k6 실행
8. Prometheus after 스냅샷 저장

초기화·수집·health 검사 중 하나라도 실패하면 실행을 중단한다.

## 시나리오와 성공 계약

### warmup

- executor: `per-vu-iterations`
- 20 VU × 1회
- 세션 20초
- max duration 30초

### measure

- 45초 뒤 시작
- `0 → 50(30s) → 150(30s) → PEAK_VUS(1m) → PEAK_VUS(2m) → 0(30s)`
- 세션 150초
- graceful ramp-down/stop 3분

measure 결과에는 상승·최고 VU 유지·하강 구간이 모두 포함된다. summary만으로 실패가 시작된 정확한 VU 단계는 알 수 없다.

### 세션 성공 조건

1. WebSocket open
2. STOMP CONNECTED
3. watch 구독에서 자기 `watcherId`의 JOIN 수신
4. chat 구독 뒤 VU 고유 payload 발신
5. backlog가 아닌 자기 live chat echo 최소 1건 수신

한 세션은 전체/watch/chat 결과를 각각 한 번만 기록한다. chat 구독 시작 전에 받은 과거 메시지는 `chat_backlog_received_total`로만 세고 live 전달 지연에서 제외한다. 공통 WebSocket/STOMP 오류는 watch와 chat 오류 양쪽에 반영되므로 두 세부 오류율은 독립된 실패 원인으로 해석하지 않는다.

## 지표

전체 지표와 measure 전용 지표를 함께 남긴다.

| 지표 | 의미 |
|---|---|
| `measure_session_attempts_total` | measure 세션 시도 수 |
| `measure_stomp_error_rate` | 세션 전체 실패율 |
| `measure_watch_error_rate` | CONNECT·자기 JOIN 미완료 또는 공통 WebSocket/STOMP 오류가 발생한 세션 비율 |
| `measure_chat_error_rate` | 자기 live echo 미수신 또는 공통 WebSocket/STOMP 오류가 발생한 세션 비율 |
| `measure_watch_connect_duration` | WebSocket open 지연 |
| `measure_watch_subscribe_ack_duration` | STOMP CONNECTED 후 구독 전송부터 자기 JOIN 수신까지 지연 |
| `measure_chat_delivery_delay` | 구독 시작 뒤 수신한 테스트 형식의 모든 live chat fan-out 메시지의 발신 시각부터 현재 VU 수신까지 지연 |
| `measure_chat_backlog_received_total` | measure 중 별도 수신한 과거 메시지 수 |
| `vus` | 실제 활성 VU. `values.max`로 최고치 검증 |
| `vus_max` | 설정상 최대 VU |

최종 성능 표는 `measure_*`를 사용하고 warmup 오류는 전체 지표와의 차이로 별도 보고한다. Trend의 p50·p95·max는 해당 milestone이나 메시지가 실제 관측되어 기록된 표본만 대상으로 하며, 관측되지 않은 실패에 timeout 값을 대신 넣지 않는다. 이후 공통 오류로 최종 실패한 세션의 앞선 milestone 표본은 남을 수 있다. 따라서 지연 백분위는 전체 시도 540개의 분포가 아니고 반드시 오류율과 함께 읽어야 한다. 자기 chat echo는 세션 성공 판정에 쓰지만 `measure_chat_delivery_delay` 자체는 자기 echo만이 아니라 수신한 live fan-out 메시지 전체를 표본으로 삼는다.

## manifest와 결과 파일

- `results/measurement-manifest.txt`
    - measurement ID, 조건·반복, 대상 SHA, 이미지 ID, Docker 사양, k6 버전
    - 실행기·수집기·측정 당시 compose 스냅샷·scenario·helper SHA256
    - 완료 상태와 시작·종료 시각
- `results/GIT_SHA.txt`: 측정 대상 애플리케이션 SHA
- `results/measurement-compose-perf-override.yml`: 보안 보강 전 최종 측정에 실제 사용한 compose override 증거 스냅샷. 재실행 설정으로 사용하지 않는다.
- `results/*-summary.json`: `setup_data`를 제거한 회차별 k6 summary
- `results/*-config-backend-*.txt`: 회차별 실제 TTL
- `results/*-metrics-backend-*-before.txt`: 인증된 실행 전 서버 메트릭
- `results/*-metrics-backend-*-after.txt`: 인증된 실행 후 서버 메트릭
- `results/<condition>-run<n>.json`: `RAW_JSON=true`일 때만 생성하는 raw 출력

summary에는 access token을 포함하는 `setup_data`를 저장하지 않는다. raw 출력은 파일 크기와 부하 생성기 디스크 I/O 영향을 피하기 위해 기본적으로 만들지 않는다. 최종 측정 뒤 Caddy의 80/443도 loopback으로 제한했으며, 측정 당시 설정은 별도 스냅샷과 SHA256으로 보존한다. 이 보강은 k6가 사용한 gateway `localhost:8080` 경로를 바꾸지 않는다.

## 집계 유효성 규칙

`collect_results.sh`는 다음을 모두 확인하고 하나라도 실패하면 0이 아닌 종료 코드로 끝난다.

- manifest `status=complete`
- manifest 조건·반복과 summary exact-set 일치
- 모든 summary의 measurement ID 일치
- manifest에 기록한 스크립트 SHA256 일치
- 전체 error/watch/chat 표본, `session_attempts_total`, `iterations` 일치
- measure error/watch/chat 표본과 `measure_session_attempts_total` 일치
- 전체 시도 - measure 시도 = warmup 20
- `vus.values.max == PEAK_VUS`
- 회차별 TTL 일치
- before/after `process_start_time_seconds` 동일
- backend process start가 회차마다 새 값
- rate-limit drop과 Hikari timeout 메트릭 존재

C는 추가로 다음을 검증한다.

- 두 backend 모두 relay publish/delivered 증분 양수
- publish failure 0
- handler failure 0
- non-self discard 0
- subscribed 1
- 상대 backend의 publish/delivered 교차 증분 차이가 `max(5건, 0.5%)` 이내

교차 증분 tolerance는 두 backend의 Prometheus를 원자적으로 동시에 읽을 수 없어 생기는 baseline 경계 오차를 위한 것이다. 허용 범위 안이라는 사실을 메시지 무손실 보증으로 해석하지 않는다. exact equality가 필요하면 before/after 수집 전에 relay 카운터가 여러 번 연속 불변인지 확인하는 quiescence barrier를 추가한다.

## 해석 원칙

- 반복 회차를 모두 보존하고 나쁜 회차를 이상치로 제외하지 않는다.
- measure 오류율과 warmup 오류를 분리해 함께 보고한다.
- 실제 300 VU 도달은 수행 사실이지 안정 용량 인증이 아니다.
- drop 0은 rate limiter가 해당 회차에서 요청을 폐기하지 않았다는 뜻일 뿐 다른 병목을 배제하지 않는다.
- drop 증가도 그것이 최초 원인인지 결과인지는 별도 시간 상관 분석 없이 확정하지 않는다.
- p50·p95·max와 회차 간 분산을 함께 본다.
- 같은 호스트 통합 결과를 운영 처리량이나 포화점으로 일반화하지 않는다.
