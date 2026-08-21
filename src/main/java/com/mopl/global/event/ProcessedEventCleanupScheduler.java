package com.mopl.global.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멱등 처리 기록 정리를 주기적으로 실행합니다.
 *
 * <p>주기 실행을 {@link ProcessedEventCleaner} 에서 분리합니다. 정리 시점을 테스트가 직접
 * 정할 수 있어야 보관 기간 경계를 확정적으로 확인할 수 있습니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    name = "mopl.kafka.processed-event.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ProcessedEventCleanupScheduler {

    private final ProcessedEventCleaner processedEventCleaner;

    public ProcessedEventCleanupScheduler(ProcessedEventCleaner processedEventCleaner) {
        this.processedEventCleaner = processedEventCleaner;
    }

    @Scheduled(fixedDelayString = "${mopl.kafka.processed-event.cleanup.interval}")
    public void clean() {
        try {
            processedEventCleaner.clean();
        } catch (RuntimeException e) {
            // 예외가 빠져나가면 스케줄러가 이 작업의 이후 실행을 등록하지 않습니다.
            log.error("멱등 처리 기록 정리 주기 실행이 실패했습니다.", e);
        }
    }
}
