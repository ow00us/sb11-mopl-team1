package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * DLT 발행 실패 카운트가 레코드별로 독립인지 검증합니다.
 *
 * <p>하나의 카운터를 공유하면 서로 다른 파티션이나 리스너의 실패가 번갈아 발생할 때
 * 카운트가 계속 초기화되어 중지 임계값에 영원히 도달하지 못합니다. 컨테이너 중지 정책
 * 전체가 이 성질에 달려 있어 별도로 고정합니다.
 */
class CountingDeadLetterRecovererTest {

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private static ConsumerRecord<String, String> record(String topic, int partition, long offset) {
        return new ConsumerRecord<>(topic, partition, offset, "key", "value");
    }

    private static final ConsumerRecordRecoverer ALWAYS_FAILS =
        (record, exception) -> {
            throw new KafkaException("DLT 발행 실패");
        };

    private static final ConsumerRecordRecoverer ALWAYS_SUCCEEDS = (record, exception) -> {
    };

    @Test
    @DisplayName("같은 레코드의 연속 실패는 누적된다")
    void sameRecord_accumulatesFailures() {
        CountingDeadLetterRecoverer recoverer = new CountingDeadLetterRecoverer(ALWAYS_FAILS, meterRegistry);
        ConsumerRecord<String, String> target = record("mopl.follow.events", 0, 10L);

        for (int i = 1; i <= 3; i++) {
            int expected = i;
            assertThatThrownBy(() -> recoverer.accept(target, new IllegalStateException()))
                .isInstanceOf(KafkaException.class);
            assertThat(recoverer.failureCount(target)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("다른 레코드가 번갈아 실패해도 각 레코드의 카운트는 초기화되지 않는다")
    void alternatingRecords_doNotResetEachOther() {
        CountingDeadLetterRecoverer recoverer = new CountingDeadLetterRecoverer(ALWAYS_FAILS, meterRegistry);
        ConsumerRecord<String, String> first = record("mopl.follow.events", 0, 10L);
        ConsumerRecord<String, String> second = record("mopl.playlist.events", 1, 77L);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> recoverer.accept(first, new IllegalStateException()))
                .isInstanceOf(KafkaException.class);
            assertThatThrownBy(() -> recoverer.accept(second, new IllegalStateException()))
                .isInstanceOf(KafkaException.class);
        }

        assertThat(recoverer.failureCount(first)).isEqualTo(3);
        assertThat(recoverer.failureCount(second)).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 토픽의 다른 파티션과 offset은 서로 다른 레코드로 센다")
    void partitionAndOffset_arePartOfIdentity() {
        CountingDeadLetterRecoverer recoverer = new CountingDeadLetterRecoverer(ALWAYS_FAILS, meterRegistry);
        ConsumerRecord<String, String> partitionZero = record("mopl.follow.events", 0, 10L);
        ConsumerRecord<String, String> partitionOne = record("mopl.follow.events", 1, 10L);
        ConsumerRecord<String, String> nextOffset = record("mopl.follow.events", 0, 11L);

        assertThatThrownBy(() -> recoverer.accept(partitionZero, new IllegalStateException()))
            .isInstanceOf(KafkaException.class);

        assertThat(recoverer.failureCount(partitionZero)).isEqualTo(1);
        assertThat(recoverer.failureCount(partitionOne)).isZero();
        assertThat(recoverer.failureCount(nextOffset)).isZero();
    }

    @Test
    @DisplayName("발행에 성공하면 해당 레코드의 카운트를 지운다")
    void successfulPublish_clearsCount() {
        ConsumerRecord<String, String> target = record("mopl.follow.events", 0, 10L);

        CountingDeadLetterRecoverer failing = new CountingDeadLetterRecoverer(ALWAYS_FAILS, meterRegistry);
        assertThatThrownBy(() -> failing.accept(target, new IllegalStateException()))
            .isInstanceOf(KafkaException.class);
        assertThat(failing.failureCount(target)).isEqualTo(1);

        CountingDeadLetterRecoverer succeeding = new CountingDeadLetterRecoverer(ALWAYS_SUCCEEDS, meterRegistry);
        succeeding.accept(target, new IllegalStateException());
        assertThat(succeeding.failureCount(target)).isZero();
    }

    @Test
    @DisplayName("forget은 추적을 중단한다")
    void forget_removesCount() {
        CountingDeadLetterRecoverer recoverer = new CountingDeadLetterRecoverer(ALWAYS_FAILS, meterRegistry);
        ConsumerRecord<String, String> target = record("mopl.follow.events", 0, 10L);

        assertThatThrownBy(() -> recoverer.accept(target, new IllegalStateException()))
            .isInstanceOf(KafkaException.class);
        assertThat(recoverer.failureCount(target)).isEqualTo(1);

        recoverer.forget(target);
        assertThat(recoverer.failureCount(target)).isZero();
    }

    @Test
    @DisplayName("DLT 로 옮긴 건수를 원본 토픽 단위로 센다")
    void countsDeadLetterRecordsByTopic() {
        CountingDeadLetterRecoverer recoverer =
            new CountingDeadLetterRecoverer(ALWAYS_SUCCEEDS, meterRegistry);

        recoverer.accept(record("mopl.follow.events", 0, 1L), new IllegalStateException());
        recoverer.accept(record("mopl.follow.events", 1, 2L), new IllegalStateException());
        recoverer.accept(record("mopl.playlist.events", 0, 1L), new IllegalStateException());

        assertThat(dltCount("mopl.follow.events")).isEqualTo(2.0);
        assertThat(dltCount("mopl.playlist.events")).isEqualTo(1.0);
    }

    /**
     * DLT 발행이 실패하면 세지 않습니다.
     *
     * <p>옮기지 못한 레코드를 옮긴 것으로 세면, DLT 지표가 조용한 상태와 DLT 발행 자체가
     * 막힌 상태를 구분할 수 없습니다.
     */
    @Test
    @DisplayName("DLT 발행에 실패하면 건수를 세지 않는다")
    void doesNotCountWhenPublishFails() {
        CountingDeadLetterRecoverer recoverer =
            new CountingDeadLetterRecoverer(ALWAYS_FAILS, meterRegistry);

        assertThatThrownBy(() ->
            recoverer.accept(record("mopl.follow.events", 0, 1L), new IllegalStateException()))
            .isInstanceOf(KafkaException.class);

        assertThat(meterRegistry.find("mopl.kafka.dlt.records").counter()).isNull();
    }

    private double dltCount(String topic) {
        return meterRegistry.get("mopl.kafka.dlt.records").tag("topic", topic).counter().count();
    }
}
