package com.mopl.global.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Kafka 도메인 이벤트의 기계 검증 가능한 카탈로그입니다.
 *
 * <p>이벤트 타입·버전·토픽·파티션 키·순서 범위와 최소 payload 필드를 한곳에서
 * 관리합니다. 생산자와 소비자가 이 값을 직접 사용하므로 문서와 구현에 같은 문자열을
 * 반복해서 적지 않습니다.
 */
public enum KafkaEventContract {

    FOLLOW_CREATED(
        "follow.created",
        1,
        MoplTopics.FOLLOW_EVENTS,
        "aggregateId",
        "NONE",
        Set.of("followerId", "followeeId")
    ),
    PLAYLIST_SUBSCRIPTION_CREATED(
        "playlist.subscription.created",
        1,
        MoplTopics.PLAYLIST_EVENTS,
        "aggregateId",
        "NONE",
        Set.of("playlistId", "playlistOwnerId", "subscriberId")
    ),
    DIRECT_MESSAGE_CREATED(
        "direct-message.created",
        1,
        MoplTopics.DIRECT_MESSAGE_EVENTS,
        "conversationId",
        "conversationId",
        Set.of(
            "directMessageId",
            "conversationId",
            "senderId",
            "receiverId",
            "contentPreview"
        )
    );

    private static final Map<Key, KafkaEventContract> BY_KEY = buildIndex();

    private final String type;
    private final int version;
    private final String topic;
    private final String partitionKeyField;
    private final String orderingScope;
    private final Set<String> requiredPayloadFields;

    KafkaEventContract(
        String type,
        int version,
        String topic,
        String partitionKeyField,
        String orderingScope,
        Set<String> requiredPayloadFields
    ) {
        this.type = type;
        this.version = version;
        this.topic = topic;
        this.partitionKeyField = partitionKeyField;
        this.orderingScope = orderingScope;
        this.requiredPayloadFields = Set.copyOf(requiredPayloadFields);
    }

    public String type() {
        return type;
    }

    public int version() {
        return version;
    }

    public String topic() {
        return topic;
    }

    public String partitionKeyField() {
        return partitionKeyField;
    }

    public String orderingScope() {
        return orderingScope;
    }

    public Set<String> requiredPayloadFields() {
        return requiredPayloadFields;
    }

    /** 이 계약의 이벤트인지 확인하고 Kafka 메시지 키를 계산합니다. */
    public String partitionKey(EventEnvelope envelope) {
        requireMatchingEnvelope(envelope);

        if ("aggregateId".equals(partitionKeyField)) {
            if (envelope.aggregateId() == null) {
                throw new EventContractViolationException("aggregateId가 없습니다.");
            }
            return envelope.aggregateId().toString();
        }

        JsonNode payload = envelope.payload();
        JsonNode value = payload == null ? null : payload.get(partitionKeyField);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new EventContractViolationException(
                "partition key 필드가 없습니다: " + partitionKeyField);
        }
        return value.asText();
    }

    /** 같은 도메인 사건을 다시 기록해도 동일한 Outbox 중복 방지 키를 만듭니다. */
    public String deduplicationKey(EventEnvelope envelope) {
        requireMatchingEnvelope(envelope);
        if (envelope.aggregateId() == null) {
            throw new EventContractViolationException("aggregateId가 없습니다.");
        }
        return type + ":" + envelope.aggregateId();
    }

    public static Optional<KafkaEventContract> find(String type, int version) {
        return Optional.ofNullable(BY_KEY.get(new Key(type, version)));
    }

    public static KafkaEventContract require(String type, int version) {
        return find(type, version)
            .orElseThrow(() -> new EventContractViolationException(
                "지원하지 않는 이벤트 type·version입니다: " + type + " v" + version));
    }

    public static List<KafkaEventContract> contracts() {
        return List.copyOf(Arrays.asList(values()));
    }

    private void requireMatchingEnvelope(EventEnvelope envelope) {
        if (envelope == null) {
            throw new EventContractViolationException("EventEnvelope이 없습니다.");
        }
        if (!type.equals(envelope.type()) || version != envelope.version()) {
            throw new EventContractViolationException(
                "카탈로그와 envelope의 type·version이 일치하지 않습니다.");
        }
    }

    private static Map<Key, KafkaEventContract> buildIndex() {
        Map<Key, KafkaEventContract> contracts = new LinkedHashMap<>();
        for (KafkaEventContract contract : values()) {
            Key key = new Key(contract.type, contract.version);
            if (contracts.putIfAbsent(key, contract) != null) {
                throw new IllegalStateException(
                    "중복된 Kafka 이벤트 type·version입니다: "
                        + contract.type + " v" + contract.version);
            }
        }
        return Map.copyOf(contracts);
    }

    private record Key(String type, int version) {
    }
}
