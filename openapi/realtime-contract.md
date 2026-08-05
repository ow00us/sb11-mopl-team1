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
| `SUBSCRIBE` | `/sub/contents/{contentId}/chat` | 콘텐츠 채팅 정책 |
| `SEND` | `/pub/contents/{contentId}/chat` | 콘텐츠 존재와 채팅 요청 검증 |
| `SUBSCRIBE` | `/sub/conversations/{conversationId}/direct-messages` | 대화 참여자 여부 |
| `SEND` | `/pub/conversations/{conversationId}/direct-messages` | 대화 참여자와 요청 검증 |

`{contentId}`와 `{conversationId}`는 UUID 형식이어야 합니다.

`/sub/**`는 서버가 브로드캐스트하는 브로커 목적지입니다. 클라이언트가
`/sub/**`로 직접 `SEND`하는 요청은 허용하지 않습니다. 권한 없는 요청은
`COMMON_403_1`을 담은 `ERROR` 프레임으로 종료합니다.

공통 인터셉터는 명령과 목적지 형태를 검증합니다. 대화 참여자 여부는 DM
인가 인터셉터가 실제 `SUBSCRIBE`·`SEND` 경로에서 추가로 검증합니다. 콘텐츠
존재와 채팅 가능 범위처럼 다른 도메인 데이터가 필요한 검증은 각 도메인
메시지 처리기가 수행합니다.
