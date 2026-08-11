# Kafka·Outbox 공통 계약

> 관련 이슈: [#187 Kafka·Outbox 적용 범위와 이벤트 envelope 계약 확정](https://github.com/ow00us/sb11-mopl-team1/issues/187)
>
> 상태: 확정 제안
>
> 결정 오너: F. 인프라·공통 작업
>
> 작성일: 2026-08-11

## 1. 결정 요약

이 문서는 도메인마다 서로 다른 Kafka·Outbox 규칙을 선택하지 않도록 공통 계약을 정의한다.

- 도메인 담당자는 이벤트의 의미, 발생 조건, 유실 영향, 업무상 순서와 최소 payload를 정의한다.
- 소비 도메인은 실제 소비 목적과 필요한 전달 신뢰성을 확인한다.
- F 공통 오너는 envelope, 토픽, 파티션 키, Outbox, 멱등성, 재시도와 DLT 정책을 확정한다.
- 카탈로그에는 생산 도메인, 소비 도메인과 F 공통 오너가 모두 확인한 이벤트만 편입한다.
- 손실 민감 이벤트는 도메인 변경과 Outbox 레코드를 같은 데이터베이스 트랜잭션에 저장한다.
- Outbox 적용 이벤트의 생산 도메인은 `KafkaTemplate`을 직접 호출하지 않는다.
- 전달 의미는 `exactly-once`가 아니라 **at-least-once + 멱등 소비**다.
- Kafka 알림 소비자는 알림을 멱등 저장하고 SSE 전송기를 직접 호출하지 않는다.
- 알림 데이터베이스 커밋 이후 내부 이벤트와 `AFTER_COMMIT` SSE 경로를 사용한다.

이 계약은 기존 Kafka·Outbox ADR을 구체화한다. 내용이 충돌하면 이 문서를 우선한다.

## 2. 결정 범위

### 포함

- 비동기 전달 이벤트의 편입 기준과 초기 이벤트 카탈로그
- 공통 이벤트 envelope와 버전 규칙
- 토픽, Consumer Group과 DLT 명명 규칙
- 이벤트별 partition key와 순서 보장 범위
- Outbox 적용 기준과 트랜잭션 경계
- 소비자 멱등 처리와 offset 완료 원칙
- Producer relay 실패와 Consumer 처리 실패의 구분
- 재시도, DLT, 실패 보존과 최소 운영 지표
- Kafka 알림 소비와 SSE 사이의 책임 경계

### 제외

- Kafka, 토픽과 Consumer Factory의 실제 Bean 구현
- Outbox 테이블과 Flyway 마이그레이션
- Outbox claim과 relay 구현
- 개별 도메인의 Producer와 Consumer 구현
- Redis Pub/Sub 기반 다중 인스턴스 실시간 relay
- `Last-Event-ID` 기반 SSE replay
- 프리미어 데이터 모델과 스케줄러 구현

제외 항목은 이 계약을 선행 조건으로 별도 이슈에서 구현한다.

## 3. 책임과 의사결정 경계

| 구분 | 책임 |
| --- | --- |
| 생산 도메인 | 실제 상태 변화, 생성 조건, aggregate, 유실 영향, 재구성 가능성, 최소 payload 정의 |
| 소비 도메인 | 소비 목적, 결과 저장, 허용 가능한 지연·유실, 업무상 순서 요구 정의 |
| F 공통 작업 | envelope, 토픽, 파티션 기준, Outbox 적용, 멱등성, 재시도·DLT와 운영 기준 확정 |

생산 도메인이 단독으로 Outbox 적용 여부, 파티션 키, 멱등 저장소와 재시도 횟수를 확정하지 않는다. F 공통 작업도 도메인 상태 변화의 의미나 알림 수신 정책을 임의로 만들지 않는다.

## 4. Kafka 비동기 이벤트 편입 기준

다음 조건을 모두 확인한다.

1. 생산 도메인의 명확한 상태 변화 또는 시간 경계가 존재한다.
2. 실제 소비자가 존재한다.
3. 동기 호출로 결합하기보다 비동기 전달할 이유가 있다.
4. 유실, 지연과 중복 발생 시 처리 정책을 정의할 수 있다.
5. payload만으로 소비하거나 합의된 조회 포트를 사용할 수 있다.

소비자가 확정되지 않은 미래 이벤트는 카탈로그에 넣지 않는다. 활동 피드처럼 소비처가 아직 없는 이벤트도 이번 범위에서 제외한다.

### Outbox 적용 판정

다음 중 하나 이상에 해당하면 Outbox를 적용한다.

- 도메인 상태 변경은 성공했지만 이벤트만 유실되면 사용자에게 영속적인 누락이 발생한다.
- 현재 상태를 조회해도 과거 발생 사실이나 정확한 발생 시점을 복원할 수 없다.
- 후속 처리가 핵심 업무 흐름이며 재계산, TTL 또는 주기적 보정으로 회복할 수 없다.

다음은 Outbox를 적용하지 않는다.

- 다음 주기에 다시 계산되는 시청자 수 스냅샷
- 원본 조회나 TTL로 복구 가능한 캐시 무효화 신호
- WebSocket, SSE 연결 상태와 Redis Pub/Sub relay 같은 휘발성 실시간 신호
- 유실을 허용하기로 명시한 단일 애플리케이션 내부 전달 이벤트

원본 데이터가 복구 가능하다는 사실만으로 영속 알림의 유실까지 허용하지 않는다. 사용자 알림함에 남아야 하는 Notification은 원본 도메인 데이터와 별개의 영속 결과로 판단한다.

## 5. 공통 이벤트 envelope

모든 Kafka 도메인 이벤트는 다음 envelope를 사용한다.

```json
{
  "eventId": "4c12804d-9cc2-4e1a-8b75-9f4ea9b9360a",
  "type": "follow.created",
  "version": 1,
  "occurredAt": "2026-08-11T03:00:00Z",
  "aggregateId": "46dd64dc-bc10-44a7-898b-cef5a5f9c748",
  "payload": {}
}
```

| 필드 | 규칙 |
| --- | --- |
| `eventId` | 생산 시 한 번 생성하는 UUID다. Outbox 재시도에서도 바꾸지 않는다. |
| `type` | `<domain>.<event>` 소문자 점 표기법과 과거형을 사용한다. |
| `version` | 양의 정수이며 최초 버전은 `1`이다. |
| `occurredAt` | 도메인 상태 변화 또는 시간 경계가 확정된 UTC 시각이다. |
| `aggregateId` | 상태 변화의 주체가 되는 aggregate 또는 이벤트 회차의 UUID다. |
| `payload` | 소비에 필요한 최소 도메인 사실만 포함한다. |

`aggregateId`와 `occurredAt`을 payload에 다시 넣지 않는다. 비밀번호, 토큰, 이메일과 같이 소비에 필요하지 않은 민감 정보는 포함하지 않는다.

Outbox relay가 같은 이벤트를 다시 발행해도 `eventId`를 새로 만들지 않는다. 알림 소비자는 이 값을 `Notification.sourceEventId`로 사용한다.

### 타입과 Java 클래스명

- 카탈로그와 Kafka 값: `follow.created`, `direct-message.created`
- Java 클래스: `FollowCreatedEvent`, `DirectMessageCreatedEvent`

Kafka 타입명에 `Event` 접미사나 Java PascalCase를 사용하지 않는다.

## 6. 스키마 버전과 배포 정책

- 선택 필드 추가처럼 기존 소비자가 무시할 수 있는 변경은 같은 버전을 유지할 수 있다.
- 필드 삭제, 이름 변경, 타입 변경과 필수 필드 추가는 breaking change이므로 `version`을 올린다.
- 소비자는 지원하는 타입과 버전을 명시적으로 검증한다.
- 지원하지 않는 타입과 버전은 재시도하지 않고 DLT로 보낸다.

breaking change는 다음 순서로 배포한다.

```text
구버전과 신버전을 함께 지원하는 Consumer 배포
→ 신버전 Producer 배포
→ 구버전 이벤트 backlog 소진 확인
→ Consumer의 구버전 지원 제거
```

Producer가 먼저 지원하지 않는 새 버전을 발행하지 않는다.

## 7. 토픽, Consumer Group과 순서

이벤트는 생산 bounded context 단위 토픽에 기록한다. 하나의 도메인 사실이 알림 외 다른 소비자에게도 사용될 수 있고 생산자가 소비 목적에 종속되는 것을 막기 위해서다.

| 생산 영역 | 토픽 |
| --- | --- |
| 팔로우 | `mopl.follow.events` |
| 플레이리스트·구독 | `mopl.playlist.events` |
| DM | `mopl.direct-message.events` |

이벤트 타입별로 토픽을 만들지 않는다. Consumer Group은 `<application>.<consumer-purpose>` 형식을 사용한다.

| 소비 목적 | Consumer Group |
| --- | --- |
| 알림 생성 | `mopl.notification` |

새로운 독립 소비 목적은 별도 Consumer Group을 사용한다. 같은 이벤트를 알림과 활동 피드가 모두 소비한다면 같은 Group을 공유하지 않는다.

DLT는 `<원본 토픽>.DLT` 형식을 사용한다.

### orderingScope

이벤트 카탈로그에는 partition key와 함께 `orderingScope`를 기록한다.

| 값 | 의미 |
| --- | --- |
| `NONE` | 소비 결과에 업무상 선후 관계가 없다. |
| `AGGREGATE` | 같은 aggregate의 lifecycle 순서를 보장한다. |
| 명시적 key | `conversationId`처럼 여러 이벤트가 공유하는 업무 키의 순서를 보장한다. |

partition key는 `orderingScope`를 구현하기 위해 선택한다. 화면 정렬 편의를 위해 순서 범위를 만들지 않는다. 서로 다른 토픽 사이에는 같은 key를 사용해도 전체 순서가 보장되지 않는다.

## 8. 초기 이벤트 카탈로그

### 8.1 `follow.created`

| 항목 | 확정 내용 |
| --- | --- |
| 생산 | C. 팔로우 |
| 소비 | D. 알림 |
| 생성 조건 | `FollowService.follow()`가 최종적으로 신규 팔로우 생성으로 판정한 경우 |
| 중복 요청 | `FollowResult.created == false`로 귀결되는 기존 팔로우 요청에서는 생성하지 않음 |
| Outbox | 적용 |
| aggregate | `followId` |
| partition key | `followId` |
| orderingScope | `NONE` |
| 알림 수신자 | `followeeId` |
| payload | `followerId`, `followeeId` |
| deduplication key | `follow.created:<followId>` |

Outbox 기록은 팔로우 INSERT와 같은 서비스 트랜잭션 안에서 신규 생성 판정에 따라 수행한다. HTTP 응답 코드를 이벤트 생성 조건으로 사용하지 않는다.

`occurredAt`은 팔로우 행의 생성 시각이다. 팔로워 이름은 payload에 넣지 않고 알림 소비자가 합의된 사용자 조회 포트를 통해 표시 이름을 조회한다. 알림 소비자가 사용자 저장소를 직접 참조하지 않는다.

### 8.2 `playlist.subscription.created`

| 항목 | 확정 내용 |
| --- | --- |
| 생산 | C. 플레이리스트 구독 |
| 소비 | D. 알림 |
| 생성 조건 | 새로운 playlist subscription 행의 INSERT가 실제 성공한 경우 |
| 중복 요청 | 기존 구독으로 인해 INSERT 결과가 `0`인 경우 생성하지 않음 |
| Outbox | 적용 |
| aggregate | `subscriptionId` |
| partition key | `subscriptionId` |
| orderingScope | `NONE` |
| 알림 수신자 | `playlistOwnerId` |
| payload | `playlistId`, `playlistOwnerId`, `subscriberId` |
| deduplication key | `playlist.subscription.created:<subscriptionId>` |

HTTP `204` 응답을 이벤트 생성 조건으로 사용하지 않는다. 새로운 구독 행이 생성된 같은 서비스 트랜잭션 안에서만 Outbox를 기록한다.

구독자 이름은 payload에 넣지 않고 알림 소비자가 합의된 사용자 조회 포트를 사용한다.

### 8.3 `direct-message.created`

| 항목 | 확정 내용 |
| --- | --- |
| 생산 | D. DM |
| 소비 | D. 알림 |
| 생성 조건 | 새로운 direct message 행이 실제 저장된 경우 |
| Outbox | 적용 |
| aggregate | `directMessageId` |
| partition key | `conversationId` |
| orderingScope | `conversationId` |
| 알림 수신자 | `receiverId` |
| payload | `directMessageId`, `conversationId`, `senderId`, `receiverId`, `contentPreview` |
| deduplication key | `direct-message.created:<directMessageId>` |

DM 수신 알림은 사용자 알림함에서 사후 조회할 수 있어야 하는 영속 알림으로 정의한다. DM과 `hasUnread`를 조회할 수 있다는 사실은 Notification 자체의 누락을 복구하지 않으므로, DM 저장과 Outbox 기록을 같은 트랜잭션에서 처리한다.

`contentPreview`는 앞뒤 공백과 연속된 줄바꿈을 정규화하고 최대 100자로 제한한 표시용 스냅샷이다. 메시지 원문 전체와 불필요한 민감 정보는 이벤트와 로그에 남기지 않는다. 발신자 이름은 payload에 넣지 않고 알림 소비자가 `senderId`로 합의된 사용자 조회 포트를 호출하여 제목 스냅샷을 만든다.

대화방 접속 여부와 관계없이 Notification을 생성한다. 대화방을 보고 있는 세션에는 중복 실시간 알림을 생략할 수 있지만, 클라이언트가 메시지를 실제로 표시한 뒤 읽음 요청을 보내기 전까지 서버가 임의로 읽음 처리하지 않는다.

Notification은 다음 대상 식별 계약을 제공한다.

```text
type: DIRECT_MESSAGE
resourceId: conversationId
sourceEntityId: directMessageId
sourceEventId: Kafka eventId
```

- `sourceEventId`는 Kafka 중복 소비를 방지한다.
- `sourceEntityId`는 알림을 발생시킨 DM을 식별한다.
- `resourceId`는 알림 선택 시 이동할 대화방을 식별한다.
- 사용자가 해당 DM까지 읽으면 같은 대화의 해당 DM까지 생성된 Notification도 함께 읽음 처리한다.

### 8.4 프리미어 이벤트

현재 프리미어 도메인 모델, 스케줄러와 알림 대상 정책이 존재하지 않으므로 `premiere.upcoming`, `premiere.started`는 초기 확정 카탈로그에서 제외한다.

프리미어 구현이 시작될 때 별도 계약 변경으로 다음 항목을 확정한다.

- 실제 상태 전환과 이벤트 발생 조건
- 프리미어 회차 식별자
- 알림 대상자 원본과 `audiencePolicy`
- `upcoming`의 유효 시한
- `started`와의 순서
- Outbox 적용 여부

소비자와 발생 조건이 확정되기 전에는 후속 Kafka 구현 범위에 포함하지 않는다.

## 9. Outbox 기록과 relay 계약

### 생산 트랜잭션

```text
도메인 조건 확인
→ 도메인 상태 변경
→ 같은 트랜잭션에서 Outbox INSERT
→ DB COMMIT
→ relay가 커밋된 Outbox를 Kafka에 발행
```

- Outbox 적용 이벤트의 생산 도메인은 `KafkaTemplate`을 직접 호출하지 않는다.
- Outbox의 `eventId`와 envelope의 `eventId`는 같다.
- relay 재시도에서도 `eventId`를 새로 생성하지 않는다.
- Kafka broker의 발행 확인을 받은 뒤 Outbox를 `PUBLISHED`로 표시한다.
- Kafka 발행 성공 후 완료 표시 전에 프로세스가 중단되면 같은 이벤트가 다시 발행될 수 있다. 소비자 멱등성이 이를 흡수한다.

### 상태 집합

| 상태 | 의미 |
| --- | --- |
| `PENDING` | 발행 대기 |
| `PROCESSING` | relay가 claim하여 발행 중 |
| `PUBLISHED` | broker의 발행 확인을 받고 완료 |
| `FAILED` | 자동 재시도를 소진한 장기 실패 |
| `EXPIRED` | 전달 유효 시한이 지난 이벤트 |
| `SKIPPED` | 운영자가 업무 영향을 확인하고 명시적으로 건너뜀 |

`SKIPPED`에는 처리자, 처리 시각과 사유를 보존한다.

### 중복 Outbox 방지

같은 도메인 사건으로 Outbox가 두 번 생성되지 않도록 생산자가 사건별 `deduplicationKey`를 만든다. 이 값은 Outbox에서 유일해야 한다.

```text
follow.created:<followId>
playlist.subscription.created:<subscriptionId>
direct-message.created:<directMessageId>
```

단순 `(aggregateType, aggregateId, eventType, eventVersion)`을 모든 이벤트의 공통 유일 키로 강제하지 않는다. 같은 aggregate에서 같은 타입의 사건이 여러 번 발생할 수 있기 때문이다.

### 순서 게이트

- `orderingScope == NONE`인 이벤트에는 선행 이벤트 게이트를 적용하지 않는다.
- 순서가 필요한 이벤트는 같은 topic과 ordering key 안에서만 선행 상태를 확인한다.
- 같은 key의 Outbox는 `occurredAt`, Outbox ID 순으로 발행한다.
- `PUBLISHED`, `EXPIRED`, `SKIPPED`는 후속 이벤트가 진행할 수 있는 상태다.
- `FAILED`는 후속 이벤트를 계속 차단한다.
- 운영자는 실패 이벤트를 같은 `eventId`로 재발행하여 `PUBLISHED`로 전환하거나 업무 영향을 확인한 뒤 사유를 남기고 `SKIPPED`로 전환할 수 있다.

## 10. 소비자 멱등 처리와 offset

공통으로 통일하는 것은 저장소 종류가 아니라 다음 불변식이다.

> 같은 소비자가 같은 eventId를 반복해서 처리해도 사용자에게 보이는 결과는 한 번만 생성된다.

- 결과 테이블에 적절한 비즈니스 유일 키가 있으면 이를 우선 사용한다.
- 별도 처리 이력이 필요하면 `(consumerName, eventId)`를 키로 하는 `processed_events`를 소비 결과와 같은 DB 트랜잭션에 저장한다.
- Redis `SETNX`와 로컬 캐시는 DB 결과와 원자적으로 묶이지 않으므로 단독 영속 멱등 수단으로 사용하지 않는다.
- record 단위 Listener와 `AckMode.RECORD`를 사용한다.
- 알림 저장과 멱등 기록의 DB 트랜잭션이 커밋된 뒤 Listener가 정상 반환된다.
- Listener가 예외를 던지면 해당 record의 offset을 성공 처리하지 않는다.
- DB 커밋 후 offset 처리 전에 프로세스가 중단되면 같은 이벤트가 다시 전달되며 멱등 처리가 중복 결과를 차단한다.

알림 도메인은 기존 부분 유일 인덱스 `(source_event_id, receiver_id) WHERE source_event_id IS NOT NULL`을 사용한다.

- Kafka로 생성하는 Notification은 `sourceEventId`를 반드시 envelope의 `eventId`로 저장한다.
- `sourceEventId == null`인 내부·수동 알림은 이 부분 인덱스의 보호 대상이 아니다.
- 같은 이벤트를 중복 소비하면 새 Notification과 `NotificationCreatedEvent`를 생성하지 않는다.

## 11. 재시도, DLT와 실패 보존

### Consumer 처리 실패

| 실패 유형 | 처리 |
| --- | --- |
| 일시적인 DB·네트워크 오류 | 최초 처리 이후 최대 3회 재시도 |
| 역직렬화 실패 | 재시도 없이 DLT |
| 지원하지 않는 type·version | 재시도 없이 DLT |
| 필수 payload 누락·계약 위반 | 재시도 없이 DLT |
| 일시 가능성을 배제하지 못한 처리 예외 | 3회 재시도 후 DLT |

일시적인 오류의 기본 backoff는 `1초 → 2초 → 4초`다.

DLT 레코드에는 다음 정보를 보존한다.

- 원본 토픽, 파티션과 offset
- `eventId`, `type`, `version`
- 예외 클래스와 오류 메시지
- 최초 실패 시각과 최종 실패 시각

초기 운영에서는 자동 재투입하지 않는다. 원인을 수정한 뒤 수동 replay하고 같은 `eventId`를 유지하여 소비자 멱등성을 검증한다.

### DLT 발행 실패

- DLT 발행이 실패하면 원본 record의 offset을 성공 처리하지 않는다.
- DLT 발행도 제한된 횟수와 backoff로 재시도한다.
- DLT 발행 재시도까지 소진되면 해당 Listener container를 중단하고 운영 경고를 발생시킨다.
- 운영자가 DLT 연결과 실패 원인을 확인한 뒤 container를 명시적으로 재개한다.

### Outbox relay 실패

- 발행 실패 횟수, 마지막 오류와 다음 시도 시각을 Outbox에 기록한다.
- backoff로 재시도하고 장기 실패는 `FAILED`로 보존한다.
- 장기 실패를 삭제하지 않고 backlog 지표와 경고 대상으로 삼는다.
- 운영자는 같은 `eventId`로 다시 relay할 수 있어야 한다.

## 12. 영속 알림과 실시간 전달 경계

### 공통 알림 경로

```text
생산 도메인 상태 변경
→ Outbox 기록
→ Kafka 발행
→ 알림 소비자
→ Notification 멱등 저장
→ 알림 DB COMMIT
→ NotificationCreatedEvent
→ NotificationSseListener(AFTER_COMMIT)
→ SSE 전송
```

- Kafka 알림 Listener는 envelope를 검증하고 Notification을 저장한다.
- Kafka 알림 Listener는 `SseEmitterManager`를 직접 호출하지 않는다.
- Notification 저장이 롤백되면 SSE를 보내지 않는다.
- SSE 연결 부재나 전송 실패는 이미 커밋된 Notification과 Kafka 소비 결과를 롤백하지 않는다.
- SSE 실패만으로 Kafka 이벤트를 다시 소비하지 않는다.
- 사용자는 `GET /api/notifications`로 놓친 영속 알림을 복구한다.
- 같은 `eventId`의 중복 소비는 추가 Notification이나 추가 SSE를 발생시키지 않는다.

### DM 실시간 전달과 영속 알림

DM에는 목적이 다른 두 전달 경로가 존재한다.

| 경로 | 목적 | 원본 | 유실 시 복구 | Outbox |
| --- | --- | --- | --- | --- |
| WebSocket·Redis relay | 대화 화면의 실시간 DM 전달 | direct message | DM 조회와 `hasUnread` | 적용하지 않음 |
| Kafka·Notification·SSE | 사용자 알림함의 영속 DM 알림 | Notification | 알림 목록 조회 | 적용 |

대화방을 보고 있는 세션에는 중복 SSE를 생략할 수 있지만 영속 Notification 생성 여부를 휘발성 접속 상태로 결정하지 않는다.

### 다수 수신자 fan-out

다수 수신자 이벤트의 payload에 거대한 `receiverIds` 배열을 넣지 않는다.

- 생산 도메인은 도메인 사실을 한 번 발행한다.
- 알림 도메인은 대상 정책과 fan-out 실행을 소유한다.
- 알림 도메인은 생산 도메인의 Repository를 직접 참조하지 않고 합의된 조회 포트 또는 자신이 관리하는 읽기 모델을 사용한다.
- 수신자는 페이지 단위로 조회하고 Notification을 `(eventId, receiverId)` 기준으로 멱등 저장한다.
- 대규모 fan-out은 Listener 호출 안에서 끝까지 처리하지 않는다. Listener는 fan-out 작업을 멱등 등록하고 별도 worker가 진행 상태를 보존한다.
- fan-out 이벤트는 카탈로그에 `audiencePolicy`를 반드시 기록한다.

`audiencePolicy` 값은 다음 중 하나다.

| 값 | 의미 |
| --- | --- |
| `EVENT_OCCURRED_AT` | 이벤트 발생 시점의 수신자 집합 |
| `FAN_OUT_STARTED_AT` | fan-out 작업 시작 시점의 수신자 집합 |

현재 확정 카탈로그의 이벤트는 모두 payload에 단일 수신자를 포함하므로 fan-out 정책을 적용하지 않는다.

## 13. 최소 운영 지표

다음 지표를 공통으로 제공한다.

- Outbox `PENDING`, `FAILED` 수와 가장 오래된 대기 시간
- relay 발행 성공, 재시도와 최종 실패 건수
- Consumer lag
- 이벤트 type별 소비 성공, 중복, 재시도와 DLT 건수
- 알림 멱등 충돌 건수
- Listener container 중단과 재개 건수
- SSE 전송 성공, 연결 부재와 전송 실패 건수

로그에는 최소 `eventId`, `type`, `aggregateId`, topic과 partition을 포함하되 payload 전체와 개인정보는 기본 출력하지 않는다.

## 14. 후속 구현

- [#188 공통 Kafka Producer·Consumer·토픽 설정 추가](https://github.com/ow00us/sb11-mopl-team1/issues/188)
- [#189 Kafka 알림 이벤트 소비·멱등 저장 리스너 구현](https://github.com/ow00us/sb11-mopl-team1/issues/189)
- [#190 Kafka 소비 알림의 SSE 전달·누락 복구 통합 검증](https://github.com/ow00us/sb11-mopl-team1/issues/190)
- Outbox 저장 모델과 Flyway 마이그레이션
- Outbox claim, Kafka relay와 순서 게이트
- Outbox 재시도, backlog 지표와 운영 복구
- Notification의 `type`, `resourceId`, `sourceEntityId` 계약 구현
- DM 읽음과 영속 DM 알림 읽음 동기화
- C 도메인 `follow.created`, `playlist.subscription.created` 생산
- D 도메인 `direct-message.created` 생산

## 15. 확정 게이트

다음 조건을 확인하면 이 문서를 팀 공통 계약으로 확정하고 #187을 완료한다.

- [ ] C 담당자가 팔로우·구독 이벤트의 생성 조건과 수신자에 사실 오류가 없음을 확인한다.
- [ ] D 담당자가 DM 한 건당 영속 알림 생성, 읽음 연동과 이동 대상 계약을 확인한다.
- [ ] F 공통 오너가 envelope, 토픽, 파티션, Outbox, 멱등성, 재시도·DLT와 배포 정책을 확인한다.
- [ ] 후속 구현 이슈가 이 문서를 선행 계약으로 연결한다.
- [ ] 계약 변경 시 이 문서를 함께 수정하고 관련 도메인에 공유한다.
