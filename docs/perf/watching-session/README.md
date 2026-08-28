# 시청 세션·같이 보기 — k6 부하 테스트 결과

## 결론

최종 재측정에서 A와 B의 measure 구간은 각각 3회 모두 오류 0%였지만, 같은 2인스턴스 조건인 C는 **67.78% / 0% / 0.19%**로 크게 갈렸다. C run1에서는 Hikari connection timeout 920건과 rate-limit drop 594건이 함께 증가했고 채팅 전달 p95가 44.64초까지 늘었다. C run2·3에서는 drop이 0이었고 오류율과 지연도 낮았다.

따라서 이 로컬 환경에서 2인스턴스의 양방향 분산 처리는 확인했지만 반복 안정성은 확인하지 못했다. 특히 C run1 때문에 300 VU를 안정적으로 처리한다고 주장할 수 없다. DB 커넥션 획득 실패와 rate limiter 동작이 대규모 실패 회차에서 함께 관찰됐으나 어느 하나를 최초 원인으로 특정할 증거는 없다.

측정 식별자는 `20260828T102549Z-1257`이며, `collect_results.sh`의 표본·구성·프로세스·relay 검증을 통과했다. 세부 자료는 다음 문서에 나누어 기록했다.

- [측정 과정 보고서](./PROCESS_REPORT.md)
- [검증·재검증 정량 보고서](./VALIDATION_REPORT.md)
- [발표 구성 제안](./PRESENTATION_GUIDE.md)
- [측정 manifest](../../../results/measurement-manifest.txt)

최종 실행은 A→C→B로 분할해 결합했다. 각 회차의 표본·TTL·프로세스·서버 지표는 검증했지만 segment별 독립 manifest는 없으므로, 세 구간의 동일 이미지·스크립트 사용은 운영 기록에 의존한다. 표준 재현은 A/B/C를 한 번의 실행으로 수행한다.

## 기존 결과를 폐기한 이유

PR의 초기 C 오류율 19.9% / 0% / 41.8%와 “전 조건·전 회차 drop 0”이라는 결론은 사용하지 않는다. 당시에는 다음 문제가 있었다.

- `localhost:8080`이 gateway가 아니라 backend-a에 직접 연결되어 C 부하가 실제 두 인스턴스에 분산되지 않았다.
- Prometheus 수집 실패로 생성된 빈 파일을 drop 0으로 해석했다.
- 조건별 캐시 TTL이 backend 컨테이너에 전달되지 않았다.
- STOMP 오류 결과의 분모가 회차마다 달랐다.

첫 절차 수정 후에도 과거 채팅 backlog가 실시간 채팅 지연에 섞였고, 자기 사용자의 JOIN과 자기 채팅 echo를 성공 조건으로 확인하지 않았으며, warmup과 measure 지표가 합산되는 문제가 남아 있었다. 이 중간 결과도 폐기했다. 최종 결과는 이 문제들을 모두 수정한 뒤 새로 측정했다.

## 측정 환경

- 측정일: 2026-08-28
- backend: `c43265484a151d120fa29ba27764c183a9eb9d31`
- frontend/gateway: `260b09f450de40596ecead19d01b866881273484`
- 호스트: Windows, Docker Desktop, 12 vCPU, Docker 메모리 약 15.6 GiB
- 배치: k6와 모든 컨테이너를 같은 호스트에서 실행
- 데이터: 계정 300개, 고정 콘텐츠 1개
- 접속 경로: k6 → gateway `localhost:8080` → backend-a/backend-b
- 진단 경로: backend-a `localhost:18080`, backend-b `localhost:18081`

같은 호스트에서 부하 생성기와 서버가 CPU·메모리·네트워크를 공유한다. 이 결과는 단일 호스트 통합 재현 결과이며 운영 환경의 절대 처리량이나 포화점이 아니다.

위 backend/frontend SHA는 기존 PR이 측정 대상으로 기록한 `main` 커밋을 로컬에서 다시 빌드한 값이며 PR 브랜치 HEAD나 최신 `develop` 애플리케이션이 아니다. 이번 재검증은 기존 결론과 같은 측정 대상을 감사한 결과이므로, 이후 애플리케이션 변경의 성능까지 일반화하지 않는다.

## 조건과 시나리오

| 조건 | backend | 콘텐츠 존재 캐시 TTL |
|---|---:|---:|
| A | 1 | 30s |
| B | 1 | 1ms |
| C | 2 | 30s |

- warmup: 20 VU, VU당 1회, 세션 20초
- measure: 45초 뒤 시작, `0 → 50(30s) → 150(30s) → 300(1m) → 300(2m) → 0(30s)`
- measure 세션: 150초
- graceful ramp-down/stop: 3분
- 세션 흐름: CONNECT → watch·chat 구독 → 자기 JOIN 확인 → heartbeat → 고유 채팅 발신 → 자기 채팅 echo 확인

아래 오류율과 지연은 `measure_*` 지표만 사용한다. measure에는 상승·300 VU 유지·하강 구간이 모두 포함되므로 어느 VU 단계에서 실패가 시작됐는지는 이 요약만으로 알 수 없다. warmup은 별도 진단값으로 남겼다. watch/chat 세부 오류는 공통 WebSocket/STOMP 오류를 함께 포함하므로 독립된 실패 경로로 해석하지 않는다.

## 최종 결과

각 회차는 warmup 20세션과 measure 540세션을 수행했고 실제 활성 VU 최대값 300에 도달했다. 지연값은 p95이며 단위는 ms다. watch ack은 STOMP CONNECTED 후 구독 전송부터 자기 JOIN까지, chat은 구독 시작 뒤 수신한 테스트 형식의 모든 live fan-out 메시지를 측정한다. Trend에는 해당 milestone이나 메시지가 실제 관측된 표본만 들어가며, 관측되지 않은 실패에 timeout 대체값은 없다. 이후 공통 오류로 최종 실패한 세션의 앞선 표본은 남을 수 있으므로 p95는 540개 전체 세션의 지연 분포가 아니다. Hikari timeout과 drop은 회차 전후 Prometheus 카운터 증분이다.

| 조건·회차 | measure 오류 | watch 오류 | chat 오류 | watch ack p95 | chat p95 | WS 연결 p95 | Hikari timeout | drop |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A1 | 0/540 (0%) | 0 | 0 | 56.05 | 39 | 29 | 20 | 0 |
| A2 | 0/540 (0%) | 0 | 0 | 66.05 | 52 | 31.05 | 20 | 0 |
| A3 | 0/540 (0%) | 0 | 0 | 85 | 64 | 40.25 | 20 | 0 |
| B1 | 0/540 (0%) | 0 | 0 | 47.05 | 26 | 17.05 | 20 | 0 |
| B2 | 0/540 (0%) | 0 | 0 | 46 | 27 | 18.05 | 20 | 0 |
| B3 | 0/540 (0%) | 0 | 0 | 60 | 37 | 25 | 20 | 0 |
| C1 | 366/540 (67.78%) | 363 | 361 | 4,426 | 44,637.8 | 14,597.2 | 920 | 594 |
| C2 | 0/540 (0%) | 0 | 0 | 45 | 33 | 20.05 | 13 | 0 |
| C3 | 1/540 (0.19%) | 1 | 1 | 46 | 34 | 18.05 | 14 | 0 |

조건별 measure 오류 합계는 A `0/1,620`, B `0/1,620`, C `367/1,620(22.65%)`다. C의 합계나 평균만 제시하면 한 회차의 붕괴와 나머지 두 회차의 차이가 가려지므로 결론에는 각 회차 값을 함께 사용한다.

## 서버 지표와 해석

### C run1

- Hikari connection timeout: backend-a 491, backend-b 429, 합계 920
- rate-limit drop: backend-a 168, backend-b 426, 합계 594
    - `chat-send`: 444
    - `heartbeat-send`: 150
    - `watch-subscribe`, `chat-subscribe`: 0
- measure watch/chat 오류: 363/361, 오류 합집합 366
- relay publish failure: 0
- relay non-self discard: 0
- 양방향 relay 구독 상태: 1

drop은 대규모 실패 회차에서만 증가했다. 따라서 기존의 “drop이 0이므로 유량 제한이 원인이 아니다”라는 결론은 성립하지 않는다. 다만 서버 카운터에 세션·시간 태그가 없고 watch/chat 세부 오류도 공통 오류를 공유하므로 어느 신호도 실패와 일대일로 대응시킬 수 없다. rate limiter나 DB 풀이 단독 원인인지, drop이 원인인지 지연·재시도의 결과인지 현재 자료만으로 판단할 수 없다.

### warmup

| 조건·회차 | warmup 오류 | Hikari timeout |
|---|---:|---:|
| A1/A2/A3 | 각 20/20 | 각 20 |
| B1/B2/B3 | 각 20/20 | 각 20 |
| C1 | 0/20 | 920 |
| C2 | 13/20 | 13 |
| C3 | 14/20 | 14 |

warmup 오류와 DB 커넥션 획득 timeout이 반복적으로 함께 관찰됐다. 다만 Hikari 카운터는 회차 전체 증분이며 phase·세션 태그가 없으므로 일대일 인과로 해석하지 않는다. A/B의 measure 오류 0%도 cold-start 경로까지 안정적이었다는 뜻은 아니다.

### 2인스턴스 분산

| 회차 | A→B 발행/전달 | B→A 발행/전달 | publish 실패 | non-self discard |
|---|---:|---:|---:|---:|
| C1 | 4,882 / 4,880 | 4,794 / 4,794 | 0 | 0 |
| C2 | 5,250 / 5,250 | 5,171 / 5,171 | 0 | 0 |
| C3 | 5,356 / 5,356 | 5,060 / 5,060 | 0 | 0 |

C1 A→B의 증분 차이 2건은 두 backend의 before 스크레이프가 약 432ms 차이로 수행된 사이 baseline에 포함된 메시지 때문에 발생했다. 종료 누적값은 발행 4,924와 전달 4,924로 같았다. 집계기는 비원자적 스크레이프를 고려해 교차 카운터 차이가 `max(5건, 해당 방향 발행·전달 최대값의 0.5%)`를 넘을 때 무효 처리하며, C1 차이는 0.041%다. 이 검증은 양방향 중계가 실제 동작했고 명시적 실패가 없었음을 보여 주지만 무손실 전달을 보증하지 않는다.

## 판단 범위

- A/B measure 구간에서는 캐시 TTL 30s와 1ms의 성능 차이를 확인하지 못했다. B의 p95가 더 낮았지만 실행 순서가 A→C→B이고 반복이 3회뿐이므로 TTL 1ms가 더 빠르다는 결론은 내리지 않는다.
- C는 실제 두 인스턴스에 분산됐지만 반복 안정성이 없다.
- C run1의 대규모 실패는 Hikari timeout과 rate-limit drop이 동시에 증가한 복합 현상이다. 현재 증거로 최초 원인은 알 수 없다.
- 300 VU 도달은 수행 사실이지 안정 용량 인증이 아니다.
- 정확한 포화점과 원인 분리를 위해서는 부하 생성기 분리, 시간대별 서버 메트릭·로그 수집, rate limiter와 DB pool을 한 변수씩 바꾸는 후속 실험이 필요하다.

## 실행 방법

자세한 준비·실행·검증 절차는 [`k6/README.md`](../../../k6/README.md)를 참조한다.

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

원본 k6 raw 출력은 기본적으로 만들지 않는다. 회차별 sanitized summary, 실제 TTL 설정, 인증된 Prometheus before/after 스냅샷, manifest를 근거로 사용한다.
