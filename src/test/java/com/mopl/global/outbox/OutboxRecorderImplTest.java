package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mopl.global.event.EventContractViolationException;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.KafkaEventContract;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class OutboxRecorderImplTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxRecorderImpl recorder = new OutboxRecorderImpl(repository);

    @Test
    @DisplayName("카탈로그와 일치하는 생산자 라우팅을 기록한다")
    void record_catalogRouting_succeeds() {
        EventEnvelope envelope = followEnvelope("follow.created", 1);
        KafkaEventContract contract = KafkaEventContract.FOLLOW_CREATED;

        recorder.record(
            envelope,
            contract.partitionKey(envelope),
            contract.orderingScope(),
            contract.deduplicationKey(envelope)
        );

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).saveAndFlush(captor.capture());
    }

    @Test
    @DisplayName("생산자가 카탈로그와 다른 파티션 키를 사용하면 기록하지 않는다")
    void record_wrongPartitionKey_fails() {
        EventEnvelope envelope = followEnvelope("follow.created", 1);

        assertThatThrownBy(() -> recorder.record(
            envelope,
            "wrong-key",
            KafkaEventContract.FOLLOW_CREATED.orderingScope(),
            KafkaEventContract.FOLLOW_CREATED.deduplicationKey(envelope)
        )).isInstanceOf(EventContractViolationException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("카탈로그에 없는 이벤트는 Outbox에 기록하지 않는다")
    void record_unregisteredEvent_fails() {
        EventEnvelope envelope = followEnvelope("follow.renamed", 1);

        assertThatThrownBy(() -> recorder.record(
            envelope,
            envelope.aggregateId().toString(),
            "NONE",
            envelope.type() + ":" + envelope.aggregateId()
        )).isInstanceOf(EventContractViolationException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("envelope이 null이면 저장소에 접근하기 전에 거부한다")
    void record_nullEnvelope_doesNotWrite() {
        assertThatThrownBy(() -> recorder.record(null, "key", "NONE", "follow.created:key"))
            .isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining("envelope");

        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "{0}이 null이면 저장하지 않는다")
    @ValueSource(strings = {"eventId", "aggregateId", "occurredAt", "payload"})
    @DisplayName("필수 값 하나가 빠진 envelope는 저장소에 접근하기 전에 거부한다")
    void record_oneRequiredValueMissing_doesNotWrite(String field) {
        EventEnvelope valid = followEnvelope("follow.created", 1);
        EventEnvelope incomplete = new EventEnvelope(
            field.equals("eventId") ? null : valid.eventId(),
            valid.type(),
            valid.version(),
            field.equals("occurredAt") ? null : valid.occurredAt(),
            field.equals("aggregateId") ? null : valid.aggregateId(),
            field.equals("payload") ? null : valid.payload()
        );

        // 잘못된 envelope로 라우팅을 먼저 계산하면 Recorder의 guard가 아닌
        // 카탈로그에서 실패하므로, 정상 대조군의 라우팅을 그대로 전달합니다.
        assertThatThrownBy(() -> recorder.record(
            incomplete,
            valid.aggregateId().toString(),
            KafkaEventContract.FOLLOW_CREATED.orderingScope(),
            KafkaEventContract.FOLLOW_CREATED.deduplicationKey(valid)
        )).isInstanceOf(EventContractViolationException.class)
            .hasMessageContaining(field);

        verifyNoInteractions(repository);
    }

    private EventEnvelope followEnvelope(String type, int version) {
        return new EventEnvelope(
            UUID.randomUUID(),
            type,
            version,
            Instant.parse("2026-08-20T01:00:00Z"),
            UUID.randomUUID(),
            JsonNodeFactory.instance.objectNode()
                .put("followerId", UUID.randomUUID().toString())
                .put("followeeId", UUID.randomUUID().toString())
        );
    }
}
