package com.mopl.global.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보관 기간을 지난 멱등 처리 기록을 지웁니다.
 *
 * <p>{@code processed_events} 는 Kafka 이벤트를 처리할 때마다 한 행이 늘고 갱신되지 않습니다.
 * 지우는 경로가 없으면 테이블과 인덱스가 소비량에 비례해 계속 커집니다.
 *
 * <p><b>보관 기간을 짧게 잡으면 안 됩니다.</b> 기록을 지운 이벤트가 다시 오면 처음 보는
 * 이벤트로 판정되어 도메인 부수 효과가 한 번 더 일어납니다. Kafka 원본 토픽 보관 기간과 DLT
 * 수동 replay 가 가능한 기간보다 길게 두어야 그 경로로 다시 들어온 이벤트가 걸러집니다.
 */
@Slf4j
@Component
public class ProcessedEventCleaner {

    private final ProcessedEventRepository processedEventRepository;
    private final Counter cleanedCounter;
    private final Clock clock;

    /** 기록한 뒤 이 기간이 지나야 지웁니다. */
    private final Duration retention;

    /**
     * 한 번의 실행이 지울 최대 건수입니다.
     *
     * <p>한 번에 오래 걸리면 그동안 잠금과 트랜잭션이 유지되어 소비 경로의 기록 선점이 함께
     * 느려집니다. 남은 것은 다음 실행으로 넘깁니다.
     */
    private final int batchSize;

    // 생성자가 둘이라 어느 쪽으로 주입할지 명시해야 합니다.
    @Autowired
    public ProcessedEventCleaner(
        ProcessedEventRepository processedEventRepository,
        MeterRegistry meterRegistry,
        @Value("${mopl.kafka.processed-event.cleanup.retention}") Duration retention,
        @Value("${mopl.kafka.processed-event.cleanup.batch-size}") int batchSize
    ) {
        this(processedEventRepository, meterRegistry, retention, batchSize, Clock.systemUTC());
    }

    /** 시각을 테스트가 정할 수 있게 하는 생성자입니다. */
    ProcessedEventCleaner(
        ProcessedEventRepository processedEventRepository,
        MeterRegistry meterRegistry,
        Duration retention,
        int batchSize,
        Clock clock
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch-size 는 1 이상이어야 합니다. 실제 " + batchSize);
        }
        if (retention.isNegative()) {
            throw new IllegalArgumentException("retention 은 음수일 수 없습니다. 실제 " + retention);
        }
        this.processedEventRepository = processedEventRepository;
        this.cleanedCounter = Counter.builder("mopl.kafka.processed.cleaned.records")
            .description("보관 기간을 지나 지운 멱등 처리 기록 수")
            .register(meterRegistry);
        this.retention = retention;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /**
     * 보관 기간을 지난 기록을 한 batch 지웁니다.
     *
     * <p>전파를 {@code REQUIRES_NEW} 로 둡니다. 정리는 짧고 독립적으로 커밋돼야 합니다.
     * 호출부의 긴 트랜잭션에 참여하면 지운 행의 잠금이 그 트랜잭션이 끝날 때까지 유지됩니다.
     *
     * @return 지운 건수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int clean() {
        Instant threshold = clock.instant().minus(retention);
        int deleted = processedEventRepository.deleteRecordedBefore(threshold, batchSize);

        if (deleted > 0) {
            cleanedCounter.increment(deleted);
            log.info("보관 기간을 지난 멱등 처리 기록을 지웠습니다. count={}, threshold={}",
                deleted, threshold);
        }
        return deleted;
    }
}
