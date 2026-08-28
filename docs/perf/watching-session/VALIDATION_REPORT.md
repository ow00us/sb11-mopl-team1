# 검증·재검증 정량 보고서

## 검증 질문

이번 재검증은 네 가지 질문을 분리해 다뤘다.

1. A/B/C가 실제로 서로 다른 조건으로 실행됐는가.
2. 2인스턴스 C가 gateway를 거쳐 두 backend에 분산됐는가.
3. watch 구독과 live chat 전달이 실제 성공한 세션만 성공으로 집계됐는가.
4. 동일 조건을 세 번 반복했을 때 오류율과 지연이 안정적으로 재현되는가.

## 측정 결과의 세대 구분

| 단계 | 대표 수치 | 판정 | 폐기 또는 채택 이유 |
|---|---|---|---|
| 초기 PR | C 오류 19.9% / 0% / 41.8% | 폐기 | gateway 우회, 빈 Prometheus 파일, TTL 미적용, 분모 불일치 |
| 1차 절차 수정 | C 오류 0% / 0.54% / 0.18% | 폐기 | backlog가 live 지연에 혼입, 자기 JOIN·자기 echo 미검증, warmup 합산 |
| 최종 계약 수정 | C measure 오류 67.78% / 0% / 0.19% | 채택 | 실제 300 VU, 자기 JOIN·echo, phase 분리, manifest·서버 지표 검증 통과 |

서로 다른 측정 계약에서 나온 수치는 성능 추세로 직접 비교하지 않는다. 최종 오류율이 이전보다 높아졌다는 사실은 애플리케이션이 악화됐다는 뜻이 아니라, 성공으로 인정하는 조건이 엄격해지고 잘못된 실행 경로가 바로잡혔다는 뜻이다.

## 최종 표본 계약

| 항목 | 회차별 값 | 검증 방식 |
|---|---:|---|
| warmup 시도 | 20 | `session_attempts_total - measure_session_attempts_total` |
| measure 시도 | 540 | `measure_session_attempts_total` |
| 전체 시도 | 560 | error/watch/chat 표본과 `iterations` 일치 |
| 최대 활성 VU | 300 | `metrics.vus.values.max` |
| 설정상 최대 VU | 300 | `metrics.vus_max.values.value` |
| 반복 | 조건별 3회 | manifest expected-set과 summary exact-set 비교 |

세션 성공은 WebSocket open, STOMP CONNECTED, 자기 사용자 JOIN 수신, 자기 고유 chat echo 수신이 모두 성립해야 한다. 과거 chat backlog는 별도 카운터로 남기고 live chat 지연에서 제외했다. 공통 WebSocket/STOMP 오류는 watch와 chat 오류 양쪽에 반영되므로 두 세부 오류율을 독립된 경로의 실패율로 해석하지 않는다.

## measure 구간 결과

지연값은 p95, 단위는 ms다. watch ack은 STOMP CONNECTED 후 구독 전송부터 자기 JOIN까지, chat은 구독 시작 뒤 수신한 테스트 형식의 모든 live fan-out 메시지를 측정한다. Trend에는 해당 milestone이나 메시지가 실제 관측된 표본만 들어가며, 관측되지 않은 실패에 timeout 대체값은 없다. 이후 공통 오류로 최종 실패한 세션의 앞선 표본은 남을 수 있다. 따라서 특히 C1 p95는 measure 540세션 전체의 지연 분포가 아니다. Hikari timeout과 drop은 서버별 before/after 카운터 증분이며 C는 두 backend 합계다.

| 조건·회차 | 전체 오류 | watch 오류 | chat 오류 | WS 연결 | watch ack | chat 전달 | Hikari timeout | drop |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A1 | 0/540 (0%) | 0 | 0 | 29 | 56.05 | 39 | 20 | 0 |
| A2 | 0/540 (0%) | 0 | 0 | 31.05 | 66.05 | 52 | 20 | 0 |
| A3 | 0/540 (0%) | 0 | 0 | 40.25 | 85 | 64 | 20 | 0 |
| B1 | 0/540 (0%) | 0 | 0 | 17.05 | 47.05 | 26 | 20 | 0 |
| B2 | 0/540 (0%) | 0 | 0 | 18.05 | 46 | 27 | 20 | 0 |
| B3 | 0/540 (0%) | 0 | 0 | 25 | 60 | 37 | 20 | 0 |
| C1 | 366/540 (67.78%) | 363 | 361 | 14,597.2 | 4,426 | 44,637.8 | 920 | 594 |
| C2 | 0/540 (0%) | 0 | 0 | 20.05 | 45 | 33 | 13 | 0 |
| C3 | 1/540 (0.19%) | 1 | 1 | 18.05 | 46 | 34 | 14 | 0 |

### 조건별 요약

| 조건 | 오류 합계 | 회차별 오류율 | watch ack p95 범위 | chat p95 범위 |
|---|---:|---:|---:|---:|
| A | 0/1,620 (0%) | 0 / 0 / 0% | 56.05~85ms | 39~64ms |
| B | 0/1,620 (0%) | 0 / 0 / 0% | 46~60ms | 26~37ms |
| C | 367/1,620 (22.65%) | 67.78 / 0 / 0.19% | 45~4,426ms | 33~44,637.8ms |

C의 오류율 범위는 67.78%p다. 합계 오류율 22.65%만 제시하면 “한 번의 대규모 붕괴와 두 번의 정상에 가까운 실행”이라는 구조를 잃으므로 회차별 값을 기본 단위로 사용한다.

## warmup과 measure 분리

| 조건·회차 | warmup 오류 | measure 오류 | Hikari timeout 증분 |
|---|---:|---:|---:|
| A1/A2/A3 | 각 20/20 | 각 0/540 | 각 20 |
| B1/B2/B3 | 각 20/20 | 각 0/540 | 각 20 |
| C1 | 0/20 | 366/540 | 920 |
| C2 | 13/20 | 0/540 | 13 |
| C3 | 14/20 | 1/540 | 14 |

A/B 여섯 회차에서 warmup 오류 20건과 Hikari timeout 20건이 반복됐고, C2·3에서도 각각 13/13과 14/14로 함께 나타났다. 이는 cold/warmup 실패와 DB 커넥션 획득 timeout이 연관될 가능성을 보여 준다.

다만 Hikari 지표는 회차 전체 카운터이고 phase·세션 태그가 없다. timeout 한 건과 실패 세션 한 건의 인과를 연결할 수 없으며, C1에서는 timeout 920건이 오류 세션 366개보다 많다. 따라서 “Hikari timeout이 최초 원인이다”가 아니라 “반복적으로 동반 관찰됐다”까지가 증거 범위다.

## C run1 붕괴

### 오류 겹침

- watch 실패: 363세션
- chat 실패: 361세션
- 전체 실패 합집합: 366세션
- watch와 chat 모두 실패: 358세션
- watch만 실패: 5세션
- chat만 실패: 3세션

이 겹침은 독립된 두 경로의 동시 실패를 뜻하지 않는다. 측정기의 공통 `sawError`가 WebSocket/STOMP 오류를 watch와 chat 양쪽에 반영하기 때문에 생길 수 있다. 표는 계측 결과를 보존하기 위한 것이며 원인 위치를 추론하는 근거로 사용하지 않는다.

### 지연

| 지표 | C1 p50 | C1 p95 | C1 max | C2/C3 p95 |
|---|---:|---:|---:|---:|
| WS 연결 | 6ms | 14.60s | 21.06s | 20.05 / 18.05ms |
| watch ack | 99ms | 4.43s | 90.15s | 45 / 46ms |
| live chat fan-out 표본 | 1.98s | 44.64s | 120.62s | 33 / 34ms |

기록된 milestone·메시지 표본에서는 서버 비즈니스 처리뿐 아니라 WebSocket 연결 자체도 느려졌다. 관측되지 않은 실패에는 timeout 대체값이 없으므로 이 백분위만으로 전체 세션의 지연 크기를 추정할 수 없다. DB 풀과 rate limiter 외에 부하 생성기와 서버가 공유한 호스트 자원 포화 등 다른 동시 요인도 배제할 수 없다.

### 서버 신호

| 신호 | backend-a | backend-b | 합계 |
|---|---:|---:|---:|
| Hikari timeout | 491 | 429 | 920 |
| rate-limit drop | 168 | 426 | 594 |
| chat-send drop | 133 | 311 | 444 |
| heartbeat-send drop | 35 | 115 | 150 |
| watch/chat subscribe drop | 0 | 0 | 0 |

drop 594건은 C1에만 나타났고 C2·3은 0이었다. 오류 증가와 drop 증가가 같은 회차에 나타난 상관은 분명하다. 그러나 서버 카운터에 세션·시간 태그가 없고 watch/chat 세부 오류도 공통 오류를 공유한다. drop이 앞선 원인인지 서버 지연 뒤의 재시도·타이밍 변화가 만든 결과인지, 유량 제한이나 DB 풀이 단독 원인인지 현재 자료로 판단할 수 없다.

## 2인스턴스 분산 검증

| 회차 | A publish | B delivered | 차이 | B publish | A delivered | 차이 |
|---|---:|---:|---:|---:|---:|---:|
| C1 | 4,882 | 4,880 | 2 (0.041%) | 4,794 | 4,794 | 0 |
| C2 | 5,250 | 5,250 | 0 | 5,171 | 5,171 | 0 |
| C3 | 5,356 | 5,356 | 0 | 5,060 | 5,060 | 0 |

모든 C 회차에서 양쪽 publish와 상대편 delivered가 양수였고 publish failure·non-self discard는 0, subscribed는 1이었다. C2의 publish 비중은 50.38%/49.62%, C3은 51.42%/48.58%로 양쪽에 실제 부하가 분산됐다.

C1의 2건 차이는 before 스크레이프 간 약 432ms 경계에서 생겼고 종료 누적값은 4,924/4,924로 같았다. 비동시 스크레이프의 교차 증분 차이는 `max(5건, 해당 방향 발행·전달 최대값의 0.5%)` 이내만 허용한다. 이 검증은 중계 경로가 동작했다는 증거이지 메시지 무손실 보증은 아니다.

## 검증 통과 항목

- manifest 상태 `complete`
- A/B/C × 3회 exact summary set
- summary measurement ID 일치
- 결과 파일에 `setup_data` 없음
- 실제 활성 VU 300
- measure/전체 표본 540/560 일치
- 실제 TTL A/C 30s, B 1ms
- 측정 중 backend 프로세스 재시작 없음
- 회차마다 backend 프로세스 새로 생성
- C 양방향 relay 양수, 명시적 publish failure·non-self discard 0
- 실행기·수집기·측정 당시 compose 스냅샷·scenario·helper SHA256 일치
- `collect_results.sh` 종료 코드 0

위 검증은 결합된 결과 세트의 exact-set·표본·서버 지표와 최종 manifest가 가리키는 파일을 확인한다. A→C→B 각 segment마다 독립 manifest가 생성된 것은 아니므로, 세 구간이 같은 이미지·스크립트를 사용했다는 운영 기록까지 암호학적으로 각각 증명하지는 않는다.

## 해석과 남은 가설

확정할 수 있는 판단은 다음과 같다.

1. A와 B의 measure 구간은 세 회차 모두 오류 0%였다.
2. C는 실제 두 인스턴스에 거의 균등하게 분산됐다.
3. C의 반복 안정성은 확인되지 않았다. 동일 조건에서 67.78%와 0%가 모두 나왔다.
4. C1의 대규모 실패에서는 Hikari timeout과 rate-limit drop이 함께 증가했다.
5. 원인은 아직 분리되지 않았다.

확정하면 안 되는 판단은 다음과 같다.

- 2인스턴스는 항상 실패한다.
- rate limiter가 유일한 원인이다.
- Hikari timeout이 모든 오류를 직접 일으켰다.
- B의 p95가 더 낮으므로 TTL 1ms가 더 빠르다.
- 300 VU는 검증된 안정 용량이다.

## 다음 재검증 제안

1. k6를 별도 호스트로 분리해 서버와 부하 생성기의 자원 경합을 제거한다.
2. 1초 간격 Hikari active/pending/timeout, PostgreSQL connection, CPU, 메모리, Redis rate-limit counter를 시계열로 저장한다.
3. STOMP 오류 유형과 세션 ID·backend ID를 회차별 로그로 보존한다.
4. `rate limiter on/off`, `Hikari pool 10/20`, `backend 1/2`를 한 번에 하나씩 바꾸는 요인 실험을 수행한다.
5. warmup 성공 기준을 별도로 두고 cold-start 실험과 warmed capacity 실험을 분리한다.
6. relay before/after 수집 전에 카운터가 2~3회 연속 변하지 않는 quiescence barrier를 두어 exact equality가 필요한 실험을 별도로 수행한다.

현재 결과는 후속 실험의 출발점이지 원인 분석의 종료점이 아니다.

후속 실험에서 최신 `develop`의 용량을 판단하려면 해당 커밋으로 backend/frontend 이미지를 다시 고정하고 전 조건을 새 측정 ID로 재실행해야 한다. 이번 수치는 기존 PR에 기록된 `main` 대상 SHA의 재검증 결과다.
