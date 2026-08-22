# WebSocket·STOMP 공통 계약

## 연결

- Handshake endpoint: `/ws` (SockJS), `/ws/websocket` (WebSocket transport)
- 허용 Origin은 `app.websocket.allowed-origins`에서 관리합니다.
- STOMP `CONNECT` 또는 `STOMP` 프레임은 `Authorization: Bearer <access-token>` 헤더가 필요합니다.
- 토큰이 없거나 유효하지 않으면 `COMMON_401_1`을 담은 `ERROR` 프레임을 반환하고 연결을 종료합니다.

## 클라이언트 인바운드 목적지

정의하지 않은 `SUBSCRIBE`·`SEND` 목적지는 기본적으로 거부합니다.

| 명령 | 허용 목적지 | 추가 도메인 검증 |
|---|---|---|
| `SUBSCRIBE` | `/sub/contents/{contentId}/watch` | 인증 사용자 여부 |
| `SUBSCRIBE` | `/sub/contents/{contentId}/chat` | 콘텐츠 존재 여부만 확인, 시청 여부는 확인하지 않음 |
| `SEND` | `/pub/contents/{contentId}/chat` | 콘텐츠 존재와 채팅 요청 검증 |
| `SUBSCRIBE` | `/sub/conversations/{conversationId}/direct-messages` | 대화 참여자 여부 |
| `SEND` | `/pub/conversations/{conversationId}/direct-messages` | 대화 참여자와 요청 검증 |
| `SEND` | `/pub/contents/{contentId}/watch/heartbeat` | 시청 세션 소유권 |

클라이언트는 `/sub/contents/{contentId}/watch` 구독 후 20초 주기로
`/pub/contents/{contentId}/watch/heartbeat`에 heartbeat를 전송합니다.

`{contentId}`와 `{conversationId}`는 UUID 형식이어야 합니다.

## 유량·구독 개수 제한

공통 인터셉터는 목적지별 정상 사용 범위를 넘는 프레임을 조용히 무시합니다.
`ERROR` 프레임을 보내지 않고 연결도 끊지 않으며, 초과 시 응답 자체가 없습니다.
클라이언트 관점에서는 "보냈지만 반영되지 않음"으로 관찰됩니다.

| 목적지 | 제한 종류 | 값 | 초과 시 동작 |
|---|---|---|---|
| `/pub/contents/{contentId}/watch/heartbeat` | 최소 전송 간격 | `heartbeat-interval` ÷ 2 (기본 10초) | 무시. `WatchingSessionService.heartbeat()` 미호출 |
| `/pub/contents/{contentId}/chat` | 최소 전송 간격 | 500ms | 무시. `ContentChatService` 미도달, 브로드캐스트 없음 |
| `/sub/contents/{contentId}/watch` | 최소 재구독 간격 | 2초 | 무시. `WatchingSessionService.start()` 미호출 |
| `/sub/contents/{contentId}/chat` | 연결당 동시 구독 개수 상한 | 20개 | 무시. 브로커에 구독이 등록되지 않고, 콘텐츠 존재 여부 조회(DB)에도 도달하지 않음 |

제한은 WebSocket 연결(세션) 단위로 적용되며, 연결이 종료되면 모든 제한 상태가
함께 사라집니다. 세션 attribute를 읽을 수 없는 경우(예: 비정상 프레임)는
차단하지 않고 통과시킵니다(fail-open) — 이 제한은 인가가 아니라 과부하·어뷰징
방어이기 때문입니다.

`/pub/conversations/{conversationId}/direct-messages`, `/sub/conversations/{conversationId}/direct-messages`는
이 제한의 적용 대상이 아닙니다. 다중 인스턴스에 걸친 사용자 단위 합산 제한도
다루지 않습니다.

`/sub/**`는 서버가 브로드캐스트하는 브로커 목적지입니다. 클라이언트가
`/sub/**`로 직접 `SEND`하는 요청은 허용하지 않습니다. 권한 없는 요청은
`COMMON_403_1`을 담은 `ERROR` 프레임으로 종료합니다.

공통 인터셉터는 명령과 목적지 형태를 검증합니다. 대화 참여자 여부는 DM
인가 인터셉터가 실제 `SUBSCRIBE`·`SEND` 경로에서 추가로 검증합니다. 콘텐츠
존재와 채팅 가능 범위처럼 다른 도메인 데이터가 필요한 검증은 각 도메인
메시지 처리기가 수행합니다.
