package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * DLT 발행이 반복 실패했을 때 컨테이너를 멈추고 그 사유를 남기는지 검증합니다.
 *
 * <p>브로커를 띄우지 않습니다. 실제 DLT 발행을 반복 실패시키려면 backoff 를 여러 번 소진해야
 * 해서 수십 초가 걸리고, 그렇게 얻는 것은 여기서 확인하는 규칙과 같습니다.
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
}
