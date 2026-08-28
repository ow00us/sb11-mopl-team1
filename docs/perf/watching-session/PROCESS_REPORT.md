# 시청 세션 부하 테스트 측정 과정 보고서

## 목적

PR #399에 기록된 “2인스턴스 오류율이 회차마다 크게 다르고 원인은 불명확하다”는 결론을 로컬 환경에서 다시 검증하고, 재현 가능한 측정 절차와 근거 파일을 PR에 남기는 것이 목적이었다. 기존 수치를 그대로 확인하는 데 그치지 않고, 그 수치가 실제 gateway·2인스턴스·설정·성공 조건을 측정했는지도 함께 감사했다.

## 진행 원칙

- 사용 중인 원본 작업 트리는 건드리지 않고 별도 임시 worktree에서 PR 브랜치를 검증했다.
- `perf.env`의 비밀값은 출력하거나 결과 파일에 저장하지 않았다.
- 오류율이 낮게 나온 회차만 선택하지 않고 모든 반복을 보존했다.
- 실행이 성공했다는 사실과 측정 결과가 유효하다는 판단을 분리했다.
- 잘못된 측정임이 확인된 결과는 문구만 완화하지 않고 폐기한 뒤 다시 실행했다.

## 오후 3시 이후 진행 경과

오후 3시 이후의 준비·진단 단계는 모든 명령에 별도 시각 로그를 남기지 않았으므로 단계 순서로 기록한다. 최종 유효 측정 구간은 manifest와 결과 파일 시각으로 분 단위까지 확인했다.

### 1. PR·환경 고정

- 기존 PR HEAD `118cea428724249e2aa493fdf127fe3e84c29065`와 측정 대상 backend/frontend SHA를 각각 확인했다.
- 로컬 전용 backend·frontend 이미지를 고정하고 Docker 이미지 ID를 manifest에 기록했다.
- 전용 PostgreSQL 데이터 경로와 고정 콘텐츠를 사용했다.
- 부하 계정 300개를 준비하고 모든 회차에서 동일 계정을 사용했다.
- Docker Desktop 재기동 후 서비스 health와 네트워크 경로를 다시 확인했다.

측정 대상은 기존 결과 파일이 가리킨 `main`의 backend `c43265484a151d120fa29ba27764c183a9eb9d31`과 frontend `260b09f450de40596ecead19d01b866881273484`다. PR 브랜치 HEAD나 최신 `develop` 애플리케이션을 측정한 것이 아니라, 기존 PR 결론과 동일한 대상의 측정 절차와 결과를 재검증했다.

### 2. 기존 결과의 유효성 감사

기존 PR 결과를 재현하는 과정에서 다음 문제가 확인됐다.

1. k6의 `localhost:8080` 요청이 gateway가 아니라 backend-a로 직접 들어가 C 조건이 실제 2인스턴스 부하가 아니었다.
2. Prometheus 수집 실패로 비어 있는 파일을 rate-limit drop 0으로 해석했다.
3. A/B/C의 TTL 설정이 backend 컨테이너에 실제 전달되지 않았다.
4. 한 세션에서 여러 오류가 기록되어 오류율 분모가 고정되지 않았다.

이 단계에서 초기 C 오류율 19.9% / 0% / 41.8%와 “전 회차 drop 0” 결론을 폐기했다.

### 3. 첫 수정 측정 뒤 두 번째 감사

실행 경로와 수집 절차를 고친 뒤 A/B/C를 다시 실행했으나, 결과 해석 단계에서 측정 의미가 아직 충분하지 않다는 점을 발견했다.

- chat 구독 직후 재생되는 과거 backlog를 실시간 chat 전달 지연으로 집계했다.
- 같은 watch topic의 첫 메시지를 구독 성공으로 보아 자기 사용자의 JOIN인지 확인하지 않았다.
- 세션 성공 조건에 자기 고유 채팅 echo 수신이 없었다.
- warmup과 measure 지표가 합산되어 300 VU 시나리오의 결과를 분리할 수 없었다.
- 중단된 실행과 이전 실행 파일의 혼입을 막는 manifest·정확한 파일 집합 검증이 없었다.
- `vus_max=300`만 확인해 실제 활성 VU가 300에 도달하지 않아도 놓칠 수 있었다.
- Redis 초기화 대상 chat key가 실제 애플리케이션 key와 달랐다.

이 결과도 최종 결론에 사용하지 않고 다시 폐기했다.

### 4. 측정 계약 보강

최종 실행 전 다음을 구현하고 smoke test와 독립 사전 감사를 통과시켰다.

- gateway와 backend 진단 포트를 분리하고 loopback으로만 노출
- A/C 30s, B 1ms TTL을 컨테이너 설정 파일로 재확인
- 실제 Redis presence·chat buffer·content existence key 초기화 및 초기화 실패 전파
- STOMP MESSAGE header·body 파싱
- JWT `sub`를 이용한 자기 JOIN 상관 확인
- VU별 고유 payload의 자기 chat echo 확인
- backlog와 live chat 지연 분리
- warmup과 measure 전용 지표 분리
- 세션당 전체/watch/chat 성공·실패 결과 각 1개 보장
- `metrics.vus.values.max`를 이용한 실제 300 VU 도달 검증
- 실행 ID, 이미지 ID, 대상 SHA, Docker 사양, k6 버전, 스크립트 SHA256 manifest 기록
- expected summary exact-set, 표본 수, TTL, 프로세스 재시작, relay 양방향 검증
- summary의 `setup_data` 제거로 access token 비저장

### 5. 최종 유효 측정

사용자와 조건 우선순위를 조정하는 과정에서 실행은 A→C→B 순서로 분할됐다. A가 끝난 뒤 B 시작 전에 한 번 중단했고, 운영 기록상 같은 측정 ID·이미지·데이터·스크립트로 C와 B를 이어서 실행했다. 이 사실을 숨기지 않고 최종 manifest에 `execution_mode=segmented`, `segment_order=ACB`와 각 구간 시각을 기록했다. 다만 현재 실행기는 호출마다 이전 결과를 지우며 segment별 manifest를 별도로 만들지 않으므로, 최종 결합 manifest만으로 각 구간의 동일성을 독립 재검증할 수는 없다.

| 구간 | KST 시작 | KST 종료 | 소요 |
|---|---:|---:|---:|
| A 3회 | 19:25:53 | 19:52:32 | 26분 39초 |
| C 3회 | 19:54:32 | 20:22:14 | 27분 42초 |
| B 3회 | 20:22:51 | 20:49:50 | 26분 59초 |
| 합계 | 19:25:53 | 20:49:50 | 구간 합계 81분 20초 |

각 회차마다 backend를 강제로 새로 만들고 health 확인, gateway DNS 대기, Redis 초기화, TTL 기록, Prometheus before 수집, k6 실행, Prometheus after 수집을 반복했다. 각 조건은 3회 모두 완료됐다.

### 6. 결과 검증과 relay 규칙 재검토

최초 집계에서 C run1 A→B relay가 발행 4,882 대 전달 4,880으로 2건 달라 `INVALID`가 발생했다. 원시값과 파일 시각을 독립 감사한 결과 이 차이는 비동시 scrape의 baseline 경계로 설명 가능했고, relay 실패로 판정할 근거가 없었다.

- before: backend-a 스크레이프 후 backend-b 스크레이프까지 약 432ms
- after: backend-a 스크레이프 후 backend-b 스크레이프까지 약 343ms
- A→B 원시 누적값: A publish `42→4,924`, B delivered `44→4,924`
- B→A 증분값: `4,794→4,794` 일치
- publish failure 0, non-self discard 0, 양쪽 subscribed 1

비원자적인 네 번의 스크레이프에 exact delta equality를 요구할 수 없으므로, 나머지 강한 검증을 유지한 채 교차 증분 차이가 `max(5건, 해당 방향 발행·전달 최대값의 0.5%)`를 넘을 때 무효 처리하도록 집계기를 수정했다. C run1 차이는 0.041%였다. 수정 뒤 전체 결과 검증은 종료 코드 0으로 통과했다.

최종 측정 뒤에는 base compose의 Caddy 80/443이 외부 인터페이스에 공개될 수 있음을 발견했다. 현재 perf override는 Caddy·gateway·backend 포트를 모두 loopback으로 제한한다. 측정에 실제 사용한 보강 전 override는 `results/measurement-compose-perf-override.yml`로 보존하고 기존 SHA256과 대조한다. Caddy는 k6의 gateway `localhost:8080` 경로에 포함되지 않아 결과를 다시 계산하지 않았지만, 이후 실행의 LAN 노출과 외부 유입 가능성은 막았다.

## 최종 검증 체크리스트

- summary 9개가 manifest의 A/B/C × 3회와 정확히 일치
- 모든 summary의 measurement ID 일치
- 모든 회차 measure 540세션, 전체 560세션
- 모든 회차 실제 활성 VU 최대 300
- 모든 회차 `setup_data`·access token 미포함
- A/C TTL 30s, B TTL 1ms 확인
- 측정 중 backend 프로세스 재시작 없음
- 회차별 backend process start 값 재사용 없음
- C 양쪽 relay publish/delivered 양수
- C publish failure 0, non-self discard 0, subscribed 1
- 실행기·수집기·측정 당시 compose 스냅샷·k6 스크립트 SHA256 일치
- `collect_results.sh` 종료 코드 0

## 현재 산출물

- `run_all.sh`: 조건별 환경 고정·초기화·실행·증거 수집
- `collect_results.sh`: 결과 exact-set·표본·VU·서버 지표 검증
- `k6/watch-chat-spike.js`: 자기 JOIN·live echo를 포함한 세션 계약
- `results/*-summary.json`: sanitized k6 summary 9개
- `results/*-config-backend-*.txt`: 실제 TTL
- `results/*-metrics-backend-*-before/after.txt`: 인증된 서버 지표
- `results/measurement-manifest.txt`: 측정 세트와 파일 해시
- `results/measurement-compose-perf-override.yml`: 최종 측정 당시 compose override 증거 스냅샷. 재실행에는 사용하지 않음
- `docs/perf/watching-session/VALIDATION_REPORT.md`: 정량 결과와 해석
- `docs/perf/watching-session/PRESENTATION_GUIDE.md`: 발표용 구성

## 남은 주의 사항

현재 트리의 summary에는 토큰이 없지만 PR 과거 커밋 `e4fa6b1c`에는 `setup_data`와 만료된 synthetic JWT가 들어간 결과 파일이 남아 있다. normal merge는 이 커밋을 대상 브랜치 이력에 포함하므로 최소한 squash merge를 선택해야 한다. squash merge도 PR과 fork의 기존 객체를 완전히 지우지는 않으므로 저장소 정책상 완전 제거가 필요하면 branch history rewrite가 필요하다. 실제 자격 증명으로 취급되는 값이었다면 이와 별개로 회전해야 한다.

이번 실행은 분할 측정이므로 표준 재현 절차는 여전히 `CONDITIONS=ABC` 한 번 실행이다. 향후 분할 재개가 필요하다면 각 segment의 이미지·스크립트·시각을 독립 보존하고 manifest를 수동 결합하지 않도록 실행기에 resume 기능을 별도 설계하는 편이 안전하다.
