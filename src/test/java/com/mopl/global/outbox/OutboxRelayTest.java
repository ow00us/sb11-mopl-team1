package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.MoplTopics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 전송 예외별 상태 기록 호출과 interrupt 복원을 검증합니다.
 * 실제 커밋·lease 보존·발행 후 상태 기록 실패는 OutboxRelayIntegrationTest에서 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(2);
    private static final int BATCH_SIZE = 10;

    @Mock
    private OutboxClaimer claimer;

    @Mock
    private OutboxStatusWriter statusWriter;

    @Mock
    private KafkaTemplate<String, EventEnvelope> kafkaTemplate;

    @Mock
    private OutboxMetrics metrics;

    @Mock
    private CompletableFuture<SendResult<String, EventEnvelope>> sendFuture;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(
            claimer, statusWriter, kafkaTemplate, new ObjectMapper(), metrics,
            BATCH_SIZE, ACK_TIMEOUT, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("발행 확인 대기가 중단되면 interrupt를 복원하고 성공·실패 상태를 기록하지 않는다")
    void publishClaimed_interrupted_restoresFlagWithoutStatusWrite() throws Exception {
        OutboxEvent event = claimOne("{}");
        when(kafkaTemplate.send(eq(MoplTopics.FOLLOW_EVENTS), eq(event.getPartitionKey()),
            any(EventEnvelope.class))).thenReturn(sendFuture);
        when(sendFuture.get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
            .thenThrow(new InterruptedException("relay stopping"));

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            assertThat(relay.publishClaimed()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(statusWriter);
        } finally {
            // 같은 worker에서 실행할 다음 테스트에 interrupt가 전파되지 않게 합니다.
            Thread.interrupted();
        }

        verify(metrics).recordBatch(eq(1), any(Duration.class));
    }

    @Test
    @DisplayName("send 자체가 동기로 실패하면 원인을 기록하고 발행 완료로 바꾸지 않는다")
    void publishClaimed_synchronousSendFailure_recordsOriginalError() {
        OutboxEvent event = claimOne("{}");
        when(kafkaTemplate.send(eq(MoplTopics.FOLLOW_EVENTS), eq(event.getPartitionKey()),
            any(EventEnvelope.class)))
            .thenThrow(new IllegalStateException("producer closed"));

        assertThat(relay.publishClaimed()).isZero();

        verify(statusWriter).markAttemptFailed(
            event.getId(), "java.lang.IllegalStateException: producer closed", NOW);
        verifyNoMoreInteractions(statusWriter);
    }

    @Test
    @DisplayName("비동기 전송이 실패하면 ExecutionException 대신 실제 원인을 기록한다")
    void publishClaimed_failedFuture_recordsUnderlyingCause() {
        OutboxEvent event = claimOne("{}");
        when(kafkaTemplate.send(eq(MoplTopics.FOLLOW_EVENTS), eq(event.getPartitionKey()),
            any(EventEnvelope.class)))
            .thenReturn(CompletableFuture.failedFuture(
                new IllegalArgumentException("broker rejected record")));

        assertThat(relay.publishClaimed()).isZero();

        verify(statusWriter).markAttemptFailed(
            event.getId(), "java.lang.IllegalArgumentException: broker rejected record", NOW);
        verifyNoMoreInteractions(statusWriter);
    }

    @Test
    @DisplayName("원인 없는 ExecutionException도 null 역참조 없이 자체 메시지로 기록한다")
    void publishClaimed_executionExceptionWithoutCause_recordsWrapper() throws Exception {
        OutboxEvent event = claimOne("{}");
        when(kafkaTemplate.send(eq(MoplTopics.FOLLOW_EVENTS), eq(event.getPartitionKey()),
            any(EventEnvelope.class))).thenReturn(sendFuture);
        when(sendFuture.get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
            .thenThrow(new ExecutionException("ack result unavailable", null));

        assertThat(relay.publishClaimed()).isZero();

        verify(statusWriter).markAttemptFailed(
            event.getId(), "java.util.concurrent.ExecutionException: ack result unavailable", NOW);
        verifyNoMoreInteractions(statusWriter);
    }

    @Test
    @DisplayName("payload를 읽지 못하면 Kafka 전송 전에 실패를 기록한다")
    void publishClaimed_invalidPayload_doesNotSend() {
        OutboxEvent event = claimOne("{");

        assertThat(relay.publishClaimed()).isZero();

        verifyNoInteractions(kafkaTemplate);
        verify(statusWriter).markAttemptFailed(
            event.getId(), "java.lang.IllegalStateException: Outbox payload 를 읽을 수 없습니다. eventId="
                + event.getEventId(), NOW);
        verifyNoMoreInteractions(statusWriter);
    }

    private OutboxEvent claimOne(String payload) {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(
            UUID.randomUUID(), "follow.created", 1, aggregateId, NOW.minusSeconds(1),
            payload, aggregateId.toString(), "NONE", "follow.created:" + aggregateId,
            NOW.minusSeconds(1));
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        when(claimer.claim(anyString(), eq(BATCH_SIZE), eq(NOW))).thenReturn(List.of(event));
        return event;
    }
}
