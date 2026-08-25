package com.mopl.global.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.EventTopicResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 선점한 Outbox 레코드를 Kafka 에 발행합니다.
 *
 * <p>broker 의 발행 확인을 받은 뒤에만 완료로 표시합니다. 확인 전에 완료로 바꾸면 발행이
 * 실패했는데도 다시 시도하지 않아 이벤트가 유실됩니다.
 *
 * <p>확인을 받고 상태를 반영하기 전에 프로세스가 종료되면 같은 eventId 가 다시 발행됩니다.
 * 계약이 at-least-once 이므로 소비자 멱등 처리가 이를 흡수합니다. 유실을 막는 쪽을
 * 택한 결과입니다.
 *
 * <p>시각은 {@link Clock} 에서 그때그때 읽습니다. 한 주기의 시작 시각을 batch 전체에 쓰면,
 * 브로커가 응답하지 않아 한 건마다 확인 한도를 채우는 상황에서 그 값이 크게 낡습니다. 그러면
 * 발행 완료 시각이 실제보다 앞서고, 실패 후 다음 시도 시각도 이미 지난 시각으로 계산되어
 * 재시도 간격이 사라집니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mopl.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private final OutboxClaimer outboxClaimer;
    private final OutboxStatusWriter outboxStatusWriter;
    private final KafkaTemplate<String, EventEnvelope> eventKafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxMetrics outboxMetrics;

    private final Clock clock;

    private final int batchSize;

    /**
     * broker 확인을 기다리는 한도입니다.
     *
     * <p>무한정 기다리면 브로커가 응답하지 않을 때 relay 주기가 통째로 멈추고, 선점한
     * 레코드의 lease 도 함께 만료됩니다. 한도를 넘으면 실패로 처리해 다음 주기에 다시
     * 시도합니다.
     */
    private final Duration ackTimeout;

    /** 선점 소유자입니다. 어느 인스턴스가 들고 있는지 운영에서 구분할 수 있어야 합니다. */
    private final String owner;

    // 생성자가 둘이라 어느 쪽으로 주입할지 명시해야 합니다.
    @Autowired
    public OutboxRelay(
        OutboxClaimer outboxClaimer,
        OutboxStatusWriter outboxStatusWriter,
        KafkaTemplate<String, EventEnvelope> eventKafkaTemplate,
        ObjectMapper objectMapper,
        OutboxMetrics outboxMetrics,
        @Value("${mopl.outbox.relay.batch-size}") int batchSize,
        @Value("${mopl.outbox.relay.ack-timeout}") Duration ackTimeout
    ) {
        this(outboxClaimer, outboxStatusWriter, eventKafkaTemplate, objectMapper, outboxMetrics,
            batchSize, ackTimeout, Clock.systemUTC());
    }

    /** 시각을 테스트가 정할 수 있게 하는 생성자입니다. */
    OutboxRelay(
        OutboxClaimer outboxClaimer,
        OutboxStatusWriter outboxStatusWriter,
        KafkaTemplate<String, EventEnvelope> eventKafkaTemplate,
        ObjectMapper objectMapper,
        OutboxMetrics outboxMetrics,
        int batchSize,
        Duration ackTimeout,
        Clock clock
    ) {
        this.outboxClaimer = outboxClaimer;
        this.outboxStatusWriter = outboxStatusWriter;
        this.eventKafkaTemplate = eventKafkaTemplate;
        this.objectMapper = objectMapper;
        this.outboxMetrics = outboxMetrics;
        this.batchSize = batchSize;
        this.ackTimeout = ackTimeout;
        this.clock = clock;
        this.owner = resolveOwner();
    }

    /**
     * 선점할 수 있는 레코드를 한 batch 발행합니다.
     *
     * @return 발행 확인까지 마친 건수
     */
    public int publishClaimed() {
        long startedAt = System.nanoTime();

        List<OutboxEvent> claimed = outboxClaimer.claim(owner, batchSize, clock.instant());
        if (claimed.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEvent event : claimed) {
            if (publish(event)) {
                published++;
            }
        }

        // 선점이 비어 있던 주기는 재지 않습니다. 0 이 섞이면 실제 발행에 걸린 시간의
        // 분포가 가려집니다.
        outboxMetrics.recordBatch(claimed.size(), Duration.ofNanos(System.nanoTime() - startedAt));

        log.debug("Outbox relay 완료. owner={}, claimed={}, published={}",
            owner, claimed.size(), published);
        return published;
    }

    private boolean publish(OutboxEvent event) {
        if (!send(event)) {
            return false;
        }

        try {
            outboxStatusWriter.markPublished(event.getId(), clock.instant());
            return true;
        } catch (RuntimeException e) {
            // 발행은 이미 끝났습니다. 여기서 실패로 기록하면 나간 이벤트가 시도 실패로
            // 집계되고, 반복되면 발행에 성공한 이벤트가 최종 실패로 남습니다. 선점을 그대로
            // 두고 lease 만료로 회수되게 합니다. 같은 eventId 가 다시 나가지만 계약이
            // at-least-once 이므로 소비자 멱등 처리가 흡수합니다.
            log.error("Outbox 발행은 마쳤지만 완료 상태를 남기지 못했습니다. 같은 이벤트가 다시 발행됩니다."
                + " eventId={}, type={}", event.getEventId(), event.getType(), e);
            return false;
        }
    }

    /**
     * 원본 값으로 만든 envelope 를 발행하고 broker 확인을 기다립니다.
     *
     * @return 확인을 받았으면 {@code true}
     */
    private boolean send(OutboxEvent event) {
        try {
            EventEnvelope envelope = toEnvelope(event);
            String topic = EventTopicResolver.resolve(event.getType(), event.getVersion());

            // 확인을 기다립니다. 기다리지 않으면 broker 가 거절해도 완료로 표시됩니다.
            eventKafkaTemplate.send(topic, event.getPartitionKey(), envelope)
                .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            // 종료 신호입니다. 여기서 상태를 쓰려 해도 커넥션 풀이 이미 닫히는 중일 수 있고,
            // 실패로 기록하면 발행됐을 수도 있는 건이 실패로 남습니다. 선점을 그대로 두고
            // lease 만료로 회수되게 합니다.
            Thread.currentThread().interrupt();
            log.warn("Outbox 발행 중 중단되었습니다. eventId={}", event.getEventId());
            return false;
        } catch (Exception e) {
            log.warn("Outbox 발행 실패. eventId={}, type={}", event.getEventId(), event.getType(), e);
            outboxStatusWriter.markAttemptFailed(event.getId(), describe(e), clock.instant());
            return false;
        }
    }

    /**
     * 저장된 값으로 envelope 를 다시 만듭니다.
     *
     * <p>eventId, type, version, occurredAt, aggregateId 를 그대로 씁니다. 재발행에서 이
     * 값들이 바뀌면 소비자의 멱등 판정이 깨집니다.
     */
    private EventEnvelope toEnvelope(OutboxEvent event) {
        try {
            return new EventEnvelope(
                event.getEventId(),
                event.getType(),
                event.getVersion(),
                event.getOccurredAt(),
                event.getAggregateId(),
                objectMapper.readTree(event.getPayload()));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Outbox payload 를 읽을 수 없습니다. eventId=" + event.getEventId(), e);
        }
    }

    /**
     * {@code last_error} 에 남길 문자열입니다.
     *
     * <p>{@link ExecutionException} 은 감싸는 껍데기라 그대로 남기면 원인이 보이지 않습니다.
     * 실제 실패 예외를 꺼내 기록합니다.
     */
    private String describe(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getName() + ": " + cause.getMessage();
    }

    private String resolveOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        String pid = String.valueOf(ProcessHandle.current().pid());
        String resolved = host + ":" + pid;

        // claim_owner 컬럼이 100자입니다.
        return resolved.length() <= 100 ? resolved : resolved.substring(0, 100);
    }
}
