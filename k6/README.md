# 시청 세션·같이 보기 k6 부하 테스트

같은 콘텐츠에 동시 접속이 몰리는 상황에서 watch·chat STOMP 경로의 포화점을 측정하기 위한 k6 스크립트 모음입니다. 측정 결과와 결론은 `docs/perf/watching-session/README.md`를 참조하세요.

## 디렉터리 구조

```
k6/
  lib/
    auth.js              CSRF → 로그인 → accessToken 획득
    stomp.js              STOMP 프레임 조립·파싱
  seed-users.js            측정용 계정 사전 생성
  watch-chat-spike.js      본 시나리오 (watch·chat 부하)
run_all.sh                 3개 조건 × 3회 반복 실행 스크립트 (저장소 루트)
collect_results.sh         결과 집계 스크립트 (저장소 루트)
docker-compose.perf-override.yml   로컬 측정용 override (저장소 루트)
```

## 사전 조건

- [k6](https://k6.io) 설치 (`brew install k6`)
- Docker Desktop, 그리고 `docker-compose.prod.yml` + `docker-compose.perf-override.yml`을 함께 기동할 수 있는 환경
- **운영 서버가 아닌 별도 호스트**에서 실행할 것. 같은 물리 호스트에 CPU를 나눠 쓰면 측정값이 왜곡됨
- macOS에서 300 VU 규모로 실행할 경우 파일 디스크립터 한도를 미리 올릴 것:
  ```bash
  ulimit -n 10240
  ```

## perf.env 준비

`.env.example`을 복사해 `perf.env`를 만들고 실제 값을 채웁니다. **`perf.env`는 git에 커밋하지 않습니다** (`.gitignore`에 포함되어 있어야 함).

```bash
cp .env.example perf.env
```

측정 전용으로 최소한 아래 값이 필요합니다.

| 변수 | 값 | 비고 |
|---|---|---|
| `MOPL_DOMAIN` | `localhost` | 로컬 측정용 |
| `BACKEND_IMAGE` / `FRONTEND_IMAGE` | 로컬 빌드 태그 | ECR 접근 권한이 없으면 대상 커밋을 로컬에서 재빌드 |
| `JWT_SECRET` / `OAUTH2_LOCAL_CREDENTIAL_VERIFICATION_SECRET` | `openssl rand -base64 32` 결과 | 서로 다른 값 2개 |
| `POSTGRES_*` | 임의 값 | 운영 값과 무관 |
| `MAIL_*` | 로컬 mailpit 등으로 형식만 충족 | 값이 없으면 기동 자체가 실패함 |
| `*_OAUTH_CLIENT_ID/SECRET` | 더미 값 | 실제 소셜 로그인 안 쓰면 호출 안 됨 |
| `IMAGE_STORAGE_*` | 비워둠 | `docker-compose.perf-override.yml`에서 `IMAGE_STORAGE_ENABLED=false`로 우회 |
| `ELASTICSEARCH_IMAGE` | nori 플러그인 포함 이미지 | 한국어 검색 인덱스가 `nori_tokenizer`를 요구함. 순정 elastic 이미지는 기동 실패함 |

`docker-compose.perf-override.yml`이 아래 두 값을 강제로 주입합니다 (compose 파일 자체에 매핑이 없어 `perf.env`만으로는 반영되지 않음):
- `IMAGE_STORAGE_ENABLED=false` — 이미지 저장소 미사용
- `KAFKA_TOPIC_VERIFY=false` — 시나리오가 쓰지 않는 토픽(`follow`, `playlist`, `direct-message`, `premiere`) 검증 생략

## 기동

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.perf-override.yml --env-file ./perf.env up -d
docker compose -f docker-compose.prod.yml -f docker-compose.perf-override.yml --env-file ./perf.env ps
```

모든 서비스가 `healthy`가 될 때까지 기다립니다.

## 계정·콘텐츠 준비

```bash
# 계정 300개 시딩 (기존 계정은 409로 스킵)
k6 run --env BASE_URL=http://localhost:8080 --env COUNT=300 k6/seed-users.js
```

관리자 계정 생성 → DB에서 `role`을 `ADMIN`으로 승격 → 로그인 → 콘텐츠 1개 생성(측정 대상 콘텐츠, multipart로 요청) 순서로 진행합니다. 상세 curl 명령은 저장소 커밋 이력 또는 팀 채널 참고.

콘텐츠 UUID를 확보하면 이후 모든 실행에서 `CONTENT_ID`로 사용합니다.

## 측정 실행

```bash
export CONTENT_ID=<위에서 확보한 콘텐츠 UUID>
export ACCOUNT_COUNT=300
export PEAK_VUS=300
export RUNS=3

./run_all.sh 2>&1 | tee results/run_all.log
./collect_results.sh | tee results/summary_table.txt
```

`run_all.sh`는 아래 3개 조건을 순서대로, 조건당 `RUNS`회 반복합니다.

1. 1인스턴스 · 캐시 TTL 30s (on)
2. 1인스턴스 · 캐시 TTL 1ms (off 근사 — `@DurationMin(millis=1)` 제약으로 0 설정 불가)
3. 2인스턴스 · 캐시 TTL 30s (Redis Pub/Sub relay 경유)

조건 전환마다 해당 backend를 `--force-recreate`하고 healthy를 확인한 뒤, 회차 시작 전 Redis presence·채팅 키를 초기화합니다.

## 결과물

- `results/GIT_SHA.txt` — 측정 대상 커밋
- `results/*-summary.json` — 회차별 k6 요약 (p50/p95/max 포함)
- `results/*-dropped-*.txt` — 회차·인스턴스별 유량 제한 드롭 카운터 스냅샷
- `results/*.json` (raw 출력) — 로컬에만 보관, 저장소에는 포함하지 않음 (용량 문제)

## 알려진 이슈

- macOS 환경에서 300 VU 동시 접속 시 회차마다 에러율이 불규칙하게 나타날 수 있음 (파일 디스크립터 한도 등 클라이언트 리소스 제약으로 추정). `ulimit -n`을 충분히 올렸는데도 재현되면, 해당 회차는 이상치로 보고 결과에서 제외하거나 별도로 명시할 것. 자세한 내용은 `docs/perf/watching-session/README.md`의 "회차 제외 사유" 참조.
- `/api/auth/csrf-token`은 GET 요청. `sign-in`은 JSON 바디(`email`, `password`) 사용.
