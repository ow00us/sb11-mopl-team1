package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class KafkaEventContractTest {

    private static final UUID EVENT_ID = UUID.fromString("3909a456-8272-45b9-b95b-2e7359742f2f");
    private static final UUID AGGREGATE_ID = UUID.fromString("9e5d00ad-8423-4b92-9052-f935d7d83c0f");
    private static final UUID OTHER_ID = UUID.fromString("7a0767b0-24c7-4ca5-a66c-eab471e8e682");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-29T00:00:00Z");

    @ParameterizedTest
    @EnumSource(KafkaEventContract.class)
    @DisplayName("모든 계약은 null envelope의 파티션 키와 중복 방지 키 계산을 거부한다")
    void rejectsNullEnvelope(KafkaEventContract contract) {
        assertThatThrownBy(() -> contract.partitionKey(null))
            .isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining("EventEnvelope");
        assertThatThrownBy(() -> contract.deduplicationKey(null))
            .isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining("EventEnvelope");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mismatchedEnvelopes")
    @DisplayName("type 불일치와 동일 type의 version 불일치를 각각 거부한다")
    void rejectsMismatchedEnvelope(
        KafkaEventContract contract, String description, EventEnvelope envelope
    ) {
        assertThatThrownBy(() -> contract.partitionKey(envelope))
            .isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining("type·version");
        assertThatThrownBy(() -> contract.deduplicationKey(envelope))
            .isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining("type·version");
    }

    static Stream<Arguments> mismatchedEnvelopes() {
        return Arrays.stream(KafkaEventContract.values()).flatMap(contract -> Stream.of(
            Arguments.of(contract, "null type", envelope(null, contract.version(), AGGREGATE_ID)),
            Arguments.of(contract, "다른 type", envelope("unknown.created", contract.version(), AGGREGATE_ID)),
            Arguments.of(contract, "이전 version", envelope(contract.type(), 0, AGGREGATE_ID)),
            Arguments.of(contract, "다음 version", envelope(contract.type(), contract.version() + 1, AGGREGATE_ID))
        ));
    }

    @ParameterizedTest
    @EnumSource(value = KafkaEventContract.class,
        names = {"FOLLOW_CREATED", "PLAYLIST_SUBSCRIPTION_CREATED"})
    @DisplayName("aggregate 기반 파티션 키는 UUID를 유지하고 누락된 aggregate를 거부한다")
    void aggregatePartitionKey_preservesIdAndRejectsMissingId(KafkaEventContract contract) {
        EventEnvelope valid = envelope(contract.type(), contract.version(), AGGREGATE_ID);
        EventEnvelope missing = envelope(contract.type(), contract.version(), null);

        assertThat(contract.partitionKey(valid)).isEqualTo(AGGREGATE_ID.toString());
        assertThatThrownBy(() -> contract.partitionKey(missing))
            .isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining("aggregateId");
    }

    @ParameterizedTest
    @EnumSource(KafkaEventContract.class)
    @DisplayName("중복 방지 키는 모든 계약에서 aggregateId 누락을 거부한다")
    void deduplicationKey_rejectsMissingAggregate(KafkaEventContract contract) {
        EventEnvelope missing = envelope(contract.type(), contract.version(), null);

        assertThatThrownBy(() -> contract.deduplicationKey(missing))
            .isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining("aggregateId");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConversationPayloads")
    @DisplayName("DM 파티션 키는 payload와 conversationId의 누락·타입·공백을 구분해 거부한다")
    void dmPartitionKey_rejectsInvalidConversationId(String description, JsonNode payload) {
        EventEnvelope envelope = new EventEnvelope(EVENT_ID, "direct-message.created", 1,
            OCCURRED_AT, AGGREGATE_ID, payload);

        assertThatThrownBy(() -> KafkaEventContract.DIRECT_MESSAGE_CREATED.partitionKey(envelope))
            .isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining("conversationId");
    }

    static Stream<Arguments> invalidConversationPayloads() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        return Stream.of(
            Arguments.of("payload null", (JsonNode) null),
            Arguments.of("필드 누락", json.objectNode()),
            Arguments.of("JSON null", json.objectNode().putNull("conversationId")),
            Arguments.of("숫자", json.objectNode().put("conversationId", 1)),
            Arguments.of("객체", json.objectNode().set("conversationId", json.objectNode())),
            Arguments.of("빈 문자열", json.objectNode().put("conversationId", "")),
            Arguments.of("공백", json.objectNode().put("conversationId", " \t\n"))
        );
    }

    @Test
    @DisplayName("DM 파티션 키는 메시지 ID가 아닌 대화 ID를 사용한다")
    void dmPartitionKey_usesConversationId() {
        JsonNode payload = JsonNodeFactory.instance.objectNode()
            .put("conversationId", OTHER_ID.toString());
        EventEnvelope envelope = new EventEnvelope(EVENT_ID, "direct-message.created", 1,
            OCCURRED_AT, AGGREGATE_ID, payload);

        assertThat(KafkaEventContract.DIRECT_MESSAGE_CREATED.partitionKey(envelope))
            .isEqualTo(OTHER_ID.toString());
    }

    @ParameterizedTest
    @EnumSource(KafkaEventContract.class)
    @DisplayName("재기록한 같은 사건은 eventId·시각이 달라도 같은 중복 방지 키를 만든다")
    void deduplicationKey_isStableForSameFact(KafkaEventContract contract) {
        EventEnvelope first = envelope(contract.type(), contract.version(), AGGREGATE_ID);
        EventEnvelope retried = new EventEnvelope(OTHER_ID, contract.type(), contract.version(),
            OCCURRED_AT.plusSeconds(1), AGGREGATE_ID, first.payload());
        EventEnvelope otherAggregate = envelope(contract.type(), contract.version(), OTHER_ID);

        assertThat(contract.deduplicationKey(first))
            .isEqualTo(contract.type() + ":" + AGGREGATE_ID)
            .isEqualTo(contract.deduplicationKey(first))
            .isEqualTo(contract.deduplicationKey(retried))
            .isNotEqualTo(contract.deduplicationKey(otherAggregate));
    }

    @Test
    @DisplayName("aggregate UUID가 같더라도 이벤트 타입이 다르면 중복 방지 키가 다르다")
    void deduplicationKey_separatesEventTypes() {
        String followKey = KafkaEventContract.FOLLOW_CREATED
            .deduplicationKey(envelope("follow.created", 1, AGGREGATE_ID));
        String subscriptionKey = KafkaEventContract.PLAYLIST_SUBSCRIPTION_CREATED
            .deduplicationKey(envelope("playlist.subscription.created", 1, AGGREGATE_ID));

        assertThat(followKey).isNotEqualTo(subscriptionKey);
    }

    @ParameterizedTest
    @EnumSource(KafkaEventContract.class)
    @DisplayName("등록된 type·version만 조회되며 다른 version은 명시적으로 거부한다")
    void lookup_checksBothTypeAndVersion(KafkaEventContract contract) {
        assertThat(KafkaEventContract.find(contract.type(), contract.version())).containsSame(contract);
        assertThat(KafkaEventContract.require(contract.type(), contract.version())).isSameAs(contract);
        assertThat(KafkaEventContract.find(contract.type(), contract.version() + 1)).isEmpty();
        assertThatThrownBy(() -> KafkaEventContract.require(contract.type(), contract.version() + 1))
            .isInstanceOf(EventContractViolationException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "unknown.created", "FOLLOW.CREATED"})
    @DisplayName("null 또는 알 수 없는 type은 조회되지 않으며 require에서 계약 오류로 거부한다")
    void lookup_rejectsUnknownType(String type) {
        assertThat(KafkaEventContract.find(type, 1)).isEmpty();
        assertThatThrownBy(() -> KafkaEventContract.require(type, 1))
            .isInstanceOf(EventContractViolationException.class);
    }

    private static EventEnvelope envelope(String type, int version, UUID aggregateId) {
        return new EventEnvelope(EVENT_ID, type, version, OCCURRED_AT, aggregateId,
            JsonNodeFactory.instance.objectNode().put("conversationId", OTHER_ID.toString()));
    }
}
