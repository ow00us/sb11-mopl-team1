package com.mopl.global.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 정리를 주기적으로 실행합니다.
 *
 * <p>relay 와 별도 작업으로 둡니다. 같은 작업에 묶으면 정리에서 난 예외가 relay 의 이후
 * 실행까지 멈춥니다. 발행이 정리보다 중요합니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mopl.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxCleanupScheduler {

    private final OutboxCleaner outboxCleaner;

    public OutboxCleanupScheduler(OutboxCleaner outboxCleaner) {
        this.outboxCleaner = outboxCleaner;
    }

    @Scheduled(fixedDelayString = "${mopl.outbox.cleanup.interval}")
    public void clean() {
        try {
            outboxCleaner.clean();
        } catch (RuntimeException e) {
            // 예외가 빠져나가면 스케줄러가 이 작업의 이후 실행을 등록하지 않습니다.
            log.error("Outbox 정리 주기 실행이 실패했습니다.", e);
        }
    }
}
