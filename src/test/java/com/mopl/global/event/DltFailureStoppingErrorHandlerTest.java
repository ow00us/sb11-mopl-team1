package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedRuntimeException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * DLT 발행이 반복 실패했을 때 컨테이너를 멈추고 그 사유를 남기는지 검증합니다.
 *
 * <p>브로커 없이 오류 경로별 상태 보존을 확인합니다. 운영에서 사용하는 handleRemaining과
 * 보조 handleOne을 구분하며, 실제 컨테이너와 health 집계의 연결은 별도 통합 테스트가 봅니다.
 *
 * <p>{@code CommonContainerStoppingErrorHandler} 는 중지를 지시한 뒤 항상 예외를 던집니다.
 * 그래서 중지 성공 여부를 예외 유무로 판정하면 성공한 중지까지 실패로 읽힙니다. 중지 뒤에
 * 이어지는 기록과 정리가 실제로 실행되는지가 이 테스트의 핵심입니다.
 */
class DltFailureStoppingErrorHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T03:00:00Z");
    private static final String GROUP = "mopl.notification";
    private static final String TOPIC = "mopl.follow.events";

    private static final ConsumerRecordRecoverer DLT_ALWAYS_FAILS =
        (record, exception) -> {
            throw new KafkaException("DLT 발행 실패");
        };

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final KafkaListenerStopTracker stopTracker = new KafkaListenerStopTracker(
        meterRegistry, Clock.fixed(NOW, ZoneId.of("UTC")));

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>(TOPIC, 0, 10L, "key", "value");
    }

    /**
     * 중지 지시를 받으면 비정상 중지 상태가 되는 컨테이너입니다.
     *
     * <p>{@code stopAbnormally} 는 다른 스레드에서 실행되므로 그 호출을 받은 시점에 상태를
     * 바꿔 둡니다.
     */
    private MessageListenerContainer stoppableContainer() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getGroupId()).thenReturn(GROUP);
        when(container.getListenerId()).thenReturn("listener-0");
        when(container.isRunning()).thenReturn(true);
        when(container.isInExpectedState()).thenReturn(true);

        org.mockito.Mockito.doAnswer(invocation -> {
            when(container.isRunning()).thenReturn(false);
            when(container.isInExpectedState()).thenReturn(false);
            return null;
        }).when(container).stopAbnormally(any());
        return container;
    }

    private DltFailureStoppingErrorHandler handler(int maxConsecutiveFailures) {
        return new DltFailureStoppingErrorHandler(
            new CountingDeadLetterRecoverer(DLT_ALWAYS_FAILS, meterRegistry),
            stopTracker,
            // 재시도 없이 곧바로 복구 단계로 보냅니다. 재시도 자체는 여기서 볼 대상이 아닙니다.
            new FixedBackOff(0L, 0L),
            maxConsecutiveFailures);
    }

    @Test
    @DisplayName("DLT 발행이 한도만큼 연속 실패하면 컨테이너를 멈춘다")
    void stopsContainer_whenDeadLetterKeepsFailing() {
        MessageListenerContainer container = stoppableContainer();
        Consumer<?, ?> consumer = mock(Consumer.class);

        handler(1).handleOne(new IllegalStateException("처리 실패"), record(), consumer, container);

        verify(container, timeout(15_000)).stopAbnormally(any());
    }

    /**
     * 사유가 없으면 운영자는 "멈췄다"만 알고 무엇을 소비하다 왜 멈췄는지는 로그를 뒤져야
     * 알 수 있습니다.
     */
    @Test
    @DisplayName("중지하면서 Consumer Group과 토픽, 원인을 남긴다")
    void recordsStopReason() {
        MessageListenerContainer container = stoppableContainer();

        handler(1).handleOne(
            new IllegalStateException("처리 실패"), record(), mock(Consumer.class), container);

        assertThat(stopTracker.lastStop(GROUP)).hasValueSatisfying(stop -> {
            assertThat(stop.groupId()).isEqualTo(GROUP);
            assertThat(stop.listenerId()).isEqualTo("listener-0");
            assertThat(stop.topic()).isEqualTo(TOPIC);
            assertThat(stop.reason()).contains("DLT 발행이 1회 연속 실패");
            assertThat(stop.stoppedAt()).isEqualTo(NOW);
        });
        assertThat(meterRegistry.get("mopl.kafka.listener.stops")
            .tag("topic", TOPIC).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("한도에 못 미치면 컨테이너를 멈추지 않는다")
    void keepsContainerRunning_belowThreshold() {
        MessageListenerContainer container = stoppableContainer();

        handler(3).handleOne(
            new IllegalStateException("처리 실패"), record(), mock(Consumer.class), container);

        verify(container, never()).stopAbnormally(any());
        assertThat(stopTracker.lastStop(GROUP)).isEmpty();
    }

    @Test
    @DisplayName("운영 handleRemaining 경로에서 임계값 전에는 재시도하고 도달하면 중지한다")
    void handleRemaining_stopsAtThresholdAndClearsFailureCount() {
        CountingDeadLetterRecoverer recoverer =
            new CountingDeadLetterRecoverer(DLT_ALWAYS_FAILS, meterRegistry);
        DltFailureStoppingErrorHandler handler = handler(recoverer, 3);
        MessageListenerContainer container = stoppableContainer();
        Consumer<?, ?> consumer = mock(Consumer.class);
        ConsumerRecord<String, String> record = record();
        IllegalStateException processingFailure = new IllegalStateException("처리 실패");

        assertThat(handler.seeksAfterHandling()).isTrue();
        for (int attempt = 1; attempt < 3; attempt++) {
            assertRetryFailure(handler, record, consumer, container, processingFailure);
            assertThat(recoverer.failureCount(record)).isEqualTo(attempt);
            verify(container, never()).stopAbnormally(any());
            assertThat(stopTracker.lastStop(GROUP)).isEmpty();
        }

        assertRetryFailure(handler, record, consumer, container, processingFailure);

        verify(container, timeout(2_000)).stopAbnormally(any());
        assertThat(container.isInExpectedState()).isFalse();
        assertThat(recoverer.failureCount(record)).isZero();
        assertThat(stopTracker.lastStop(GROUP)).hasValueSatisfying(stop -> {
            assertThat(stop.reason()).contains("DLT 발행이 3회 연속 실패", TOPIC + "-0@10");
            assertThat(stop.stoppedAt()).isEqualTo(NOW);
        });
        assertThat(meterRegistry.get("mopl.kafka.listener.stops")
            .tag("topic", TOPIC).counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("중지 지시 후에도 기대 상태이면 실패 카운트를 보존한다")
    void handleRemaining_retainsFailuresUntilAbnormalStopIsObserved() {
        CountingDeadLetterRecoverer recoverer =
            new CountingDeadLetterRecoverer(DLT_ALWAYS_FAILS, meterRegistry);
        DltFailureStoppingErrorHandler handler = handler(recoverer, 1);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getGroupId()).thenReturn(GROUP);
        when(container.getListenerId()).thenReturn("listener-0");
        // 실행이 끝났어도 비정상 중지 상태가 확인되지 않으면 성공으로 간주하면 안 됩니다.
        when(container.isRunning()).thenReturn(false);
        when(container.isInExpectedState()).thenReturn(true);
        ConsumerRecord<String, String> record = record();

        assertRetryFailure(handler, record, mock(Consumer.class), container,
            new IllegalStateException("처리 실패"));

        verify(container, timeout(2_000)).stopAbnormally(any());
        assertThat(recoverer.failureCount(record)).isEqualTo(1);
        assertThat(stopTracker.lastStop(GROUP)).hasValueSatisfying(stop ->
            assertThat(stop.reason()).contains("DLT 발행이 1회 연속 실패"));
    }

    @Test
    @DisplayName("중지 대기에서 예외가 발생해도 원래 처리 오류와 실패 카운트를 보존한다")
    void handleRemaining_stopFailureDoesNotReplaceOriginalFailure() {
        CountingDeadLetterRecoverer recoverer =
            new CountingDeadLetterRecoverer(DLT_ALWAYS_FAILS, meterRegistry);
        DltFailureStoppingErrorHandler handler = handler(recoverer, 1);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getGroupId()).thenReturn(GROUP);
        when(container.getListenerId()).thenReturn("listener-0");
        when(container.isRunning()).thenThrow(new IllegalStateException("중지 상태 확인 실패"));
        when(container.isInExpectedState()).thenReturn(true);
        ConsumerRecord<String, String> record = record();
        IllegalStateException processingFailure = new IllegalStateException("원래 처리 실패");

        assertRetryFailure(handler, record, mock(Consumer.class), container, processingFailure);

        verify(container, timeout(2_000)).stopAbnormally(any());
        assertThat(recoverer.failureCount(record)).isEqualTo(1);
        assertThat(stopTracker.lastStop(GROUP)).isPresent();
    }

    @Test
    @DisplayName("DLT 복구가 성공하면 다음 레코드로 seek하고 중지하지 않는다")
    void handleRemaining_successfulRecoveryDoesNotStopContainer() {
        ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
        CountingDeadLetterRecoverer recoverer =
            new CountingDeadLetterRecoverer(delegate, meterRegistry);
        DltFailureStoppingErrorHandler handler = handler(recoverer, 1);
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        ConsumerRecord<String, String> failed = record();
        ConsumerRecord<String, String> next =
            new ConsumerRecord<>(TOPIC, 0, 11L, "next-key", "next-value");
        IllegalStateException processingFailure = new IllegalStateException("처리 실패");

        assertThatCode(() -> handler.handleRemaining(
            processingFailure, List.of(failed, next), consumer, container))
            .doesNotThrowAnyException();

        verify(delegate).accept(failed, processingFailure);
        verify(consumer).seek(new TopicPartition(TOPIC, 0), 11L);
        verify(container, never()).stopAbnormally(any());
        assertThat(recoverer.failureCount(failed)).isZero();
        assertThat(stopTracker.lastStop(GROUP)).isEmpty();
    }

    @Test
    @DisplayName("빈 레코드 목록에서는 상위 핸들러의 정보 부족 오류를 그대로 전파한다")
    void handleRemaining_emptyRecordsPreservesNoRecordContract() {
        assertEmptyRecordsFailure(new IllegalStateException("처리 실패"), "no record information");
    }

    @Test
    @DisplayName("빈 목록의 역직렬화 오류는 ErrorHandlingDeserializer 안내를 보존한다")
    void handleRemaining_emptyRecordsPreservesSerializationContract() {
        assertEmptyRecordsFailure(new SerializationException("역직렬화 실패"),
            "ErrorHandlingDeserializer");
    }

    private void assertEmptyRecordsFailure(Exception processingFailure, String expectedMessage) {
        ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
        DltFailureStoppingErrorHandler handler = handler(
            new CountingDeadLetterRecoverer(delegate, meterRegistry), 1);
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        Throwable failure = catchThrowable(() -> handler.handleRemaining(
            processingFailure, List.of(), consumer, container));

        assertThat(failure).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(expectedMessage);
        assertThat(failure.getCause()).isSameAs(processingFailure);
        verifyNoInteractions(delegate, consumer, container);
        assertThat(stopTracker.lastStop(GROUP)).isEmpty();
        assertThat(meterRegistry.find("mopl.kafka.listener.stops").counter()).isNull();
    }

    private void assertRetryFailure(
        DltFailureStoppingErrorHandler handler, ConsumerRecord<?, ?> record,
        Consumer<?, ?> consumer, MessageListenerContainer container, Exception processingFailure
    ) {
        Throwable failure = catchThrowable(() -> handler.handleRemaining(
            processingFailure, List.of(record), consumer, container));

        assertThat(failure).isInstanceOf(NestedRuntimeException.class)
            .hasMessage("Record in retry and not yet recovered");
        // 중지 핸들러의 "Stopped container" 신호로 원래 예외가 덮이지 않아야 합니다.
        assertThat(failure.getCause()).isSameAs(processingFailure);
    }

    private DltFailureStoppingErrorHandler handler(
        CountingDeadLetterRecoverer recoverer, int maxConsecutiveFailures
    ) {
        return new DltFailureStoppingErrorHandler(
            recoverer, stopTracker, new FixedBackOff(0L, 0L), maxConsecutiveFailures);
    }
}
