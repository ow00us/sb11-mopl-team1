package com.mopl.global.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbox 전달 상태를 지표로 노출합니다.
 *
 * <p>발행 실패는 조용히 쌓입니다. 도메인 요청은 정상 응답을 받고 커밋까지 끝나므로, 이벤트가
 * 나가지 않아도 API 지표에는 아무 흔적이 없습니다. 대기 건수와 가장 오래된 대기 이벤트의
 * 경과 시간이 그 상황을 드러내는 유일한 신호입니다.
 *
 * <p>gauge 값은 주기적으로 갱신한 값을 읽습니다. 수집 시점마다 집계 질의를 돌리면 수집
 * 주기가 곧 데이터베이스 부하가 되고, 발행 완료 레코드가 쌓일수록 그 비용이 커집니다.
 *
 * <p>집계 질의는 모두 부분 인덱스로 처리됩니다. 상태별 부분 인덱스가 없으면 발행을 마친
 * 행까지 전부 훑게 되고, 그 비용이 갱신 주기마다 발생합니다.
 *
 * <p>eventId 나 aggregateId 는 태그로 쓰지 않습니다. 값의 종류가 무한해서 시계열이 그만큼
 * 늘어납니다.
 */
@Slf4j
@Component
public class OutboxMetrics {

    private final OutboxEventRepository outboxEventRepository;

    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong claimedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    private final Counter publishedCounter;
    private final Counter retriedCounter;
    private final Counter exhaustedCounter;
    private final DistributionSummary batchSizeSummary;
    private final Timer relayTimer;

    public OutboxMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;

        gauge(meterRegistry, "mopl.outbox.events", "pending", pendingCount,
            "발행을 기다리는 Outbox 레코드 수");
        gauge(meterRegistry, "mopl.outbox.events", "claimed", claimedCount,
            "relay 가 선점 중인 Outbox 레코드 수");
        gauge(meterRegistry, "mopl.outbox.events", "failed", failedCount,
            "자동 재시도를 멈춘 Outbox 레코드 수");

        Gauge.builder("mopl.outbox.oldest.pending.age", oldestPendingAgeSeconds, AtomicLong::doubleValue)
            .description("가장 오래된 발행 대기 이벤트의 발생 후 경과 시간")
            .baseUnit("seconds")
            .register(meterRegistry);

        this.publishedCounter = counter(meterRegistry, "published", "발행 확인까지 마친 건수");
        this.retriedCounter = counter(meterRegistry, "retried", "실패 후 다시 시도하기로 한 건수");
        this.exhaustedCounter = counter(meterRegistry, "exhausted", "최대 시도 횟수를 넘겨 최종 실패로 남긴 건수");

        this.batchSizeSummary = DistributionSummary.builder("mopl.outbox.relay.batch.size")
            .description("한 주기에 선점한 레코드 수")
            .register(meterRegistry);

        this.relayTimer = Timer.builder("mopl.outbox.relay.duration")
            .description("한 주기의 선점부터 상태 반영까지 걸린 시간")
            .register(meterRegistry);
    }

    private void gauge(
        MeterRegistry registry, String name, String state, AtomicLong value, String description
    ) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
            .tag("state", state)
            .description(description)
            .register(registry);
    }

    private Counter counter(MeterRegistry registry, String outcome, String description) {
        return Counter.builder("mopl.outbox.relay.records")
            .tag("outcome", outcome)
            .description(description)
            .register(registry);
    }

    /**
     * gauge 값을 다시 읽어옵니다.
     *
     * <p>집계에 실패해도 예외를 던지지 않습니다. 지표 수집이 relay 나 애플리케이션 동작을
     * 멈추게 해서는 안 됩니다. 갱신되지 않은 값은 직전 값으로 남고, 그 자체가 수집이
     * 멈췄다는 신호가 됩니다.
     */
    public void refresh(Instant now) {
        try {
            pendingCount.set(outboxEventRepository.countByStatus(OutboxStatus.PENDING));
            claimedCount.set(outboxEventRepository.countByClaimOwnerIsNotNull());
            failedCount.set(outboxEventRepository.countByStatus(OutboxStatus.FAILED));

            Instant oldest = outboxEventRepository.findOldestOccurredAt(OutboxStatus.PENDING);
            oldestPendingAgeSeconds.set(oldest == null
                ? 0L
                : Math.max(0L, Duration.between(oldest, now).toSeconds()));
        } catch (RuntimeException e) {
            log.warn("Outbox 지표를 갱신하지 못했습니다.", e);
        }
    }

    public void recordPublished() {
        publishedCounter.increment();
    }

    public void recordRetried() {
        retriedCounter.increment();
    }

    public void recordExhausted() {
        exhaustedCounter.increment();
    }

    public void recordBatch(int claimedSize, Duration elapsed) {
        batchSizeSummary.record(claimedSize);
        relayTimer.record(elapsed);
    }
}
