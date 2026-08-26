package com.mopl.global.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * 모든 Kafka 도메인 이벤트가 공유하는 envelope입니다. 계약은 docs 의 Kafka·Outbox
 * 공통 계약(#187) §5 를 따릅니다.
 *
 * <p>payload 를 도메인 타입으로 바로 매핑하지 않고 {@link JsonNode} 로 둡니다.
 * 계약이 소비자에게 type 과 version 을 먼저 검증하도록 요구하는데, 타입을 미리
 * 확정해 역직렬화하면 검증 전에 실패하기 때문입니다. Kafka 의 타입 헤더에 의존하면
 * 계약의 type 필드가 아니라 Java 클래스명이 사실상의 계약이 되는 문제도 있습니다.
 *
 * <p>도메인 소비자는 type·version 을 검증한 뒤 ObjectMapper 로 payload 를 자기
 * 타입으로 변환합니다.
 *
 * @param eventId     생산 시 한 번 생성하는 UUID. Outbox 재발행에서도 바꾸지 않는다.
 * @param type        {@code <domain>.<event>} 소문자 점 표기법과 과거형.
 * @param version     양의 정수이며 최초 버전은 1.
 * @param occurredAt  도메인 상태 변화 또는 시간 경계가 확정된 UTC 시각.
 * @param aggregateId 상태 변화의 주체가 되는 aggregate 또는 이벤트 회차의 UUID.
 * @param payload     소비에 필요한 최소 도메인 사실. aggregateId·occurredAt 을 다시 넣지 않는다.
 */
public record EventEnvelope(
    UUID eventId,
    String type,
    int version,
    Instant occurredAt,
    UUID aggregateId,
    JsonNode payload
) {
}
