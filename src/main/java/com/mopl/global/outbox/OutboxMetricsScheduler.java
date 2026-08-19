package com.mopl.global.outbox;

import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox gauge 값을 주기적으로 갱신합니다.
 *
 * <p>주기 실행을 {@link OutboxMetrics} 에서 분리합니다. 지표 값을 확인하는 테스트가 갱신
 * 시점을 직접 정할 수 있어야 하고, 갱신 주기가 도는 상태에서는 그 값이 언제 바뀌는지
 * 확정되지 않습니다.
 */
@Component
@ConditionalOnProperty(name = "mopl.outbox.metrics.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxMetricsScheduler {

    private final OutboxMetrics outboxMetrics;

    public OutboxMetricsScheduler(OutboxMetrics outboxMetrics) {
        this.outboxMetrics = outboxMetrics;
    }

    @Scheduled(fixedDelayString = "${mopl.outbox.metrics.refresh-interval}")
    public void refresh() {
        outboxMetrics.refresh(Instant.now());
    }
}
