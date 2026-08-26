package com.mopl.global.outbox;

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
 * 보관 기간을 지난 발행 완료 Outbox 레코드를 지웁니다.
 *
 * <p>{@code outbox_events} 는 도메인 사건마다 한 행이 늘고, 발행에 성공해도 상태만 바뀝니다.
 * 지우는 경로가 없으면 저장 공간과 함께 백업, 마이그레이션 비용이 계속 커집니다.
 *
 * <p>발행 직후에 지우지 않습니다. 발행 확인을 받고 상태를 반영하기 전에 프로세스가 종료되면
 * 같은 이벤트가 다시 발행되는데, 그 흔적이 남아 있어야 무슨 일이 있었는지 확인할 수 있습니다.
 * 소비 지연이나 재처리 요청이 들어오는 기간만큼은 남깁니다.
 */
@Slf4j
@Component
public class OutboxCleaner {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxMetrics outboxMetrics;
    private final Clock clock;

    /** 발행을 마친 뒤 이 기간이 지나야 지웁니다. */
    private final Duration retention;

    /**
     * 한 번의 실행이 지울 최대 건수입니다.
     *
     * <p>한 번에 오래 걸리면 그동안 잠금과 트랜잭션이 유지되어 relay 의 선점과 도메인
     * 트랜잭션이 함께 느려집니다. 남은 것은 다음 실행으로 넘깁니다.
     */
    private final int batchSize;

    // 생성자가 둘이라 어느 쪽으로 주입할지 명시해야 합니다.
    @Autowired
    public OutboxCleaner(
        OutboxEventRepository outboxEventRepository,
        OutboxMetrics outboxMetrics,
        @Value("${mopl.outbox.cleanup.retention}") Duration retention,
        @Value("${mopl.outbox.cleanup.batch-size}") int batchSize
    ) {
        this(outboxEventRepository, outboxMetrics, retention, batchSize, Clock.systemUTC());
    }

    /** 시각을 테스트가 정할 수 있게 하는 생성자입니다. */
    OutboxCleaner(
        OutboxEventRepository outboxEventRepository,
        OutboxMetrics outboxMetrics,
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
        this.outboxEventRepository = outboxEventRepository;
        this.outboxMetrics = outboxMetrics;
        this.retention = retention;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /**
     * 보관 기간을 지난 발행 완료 레코드를 한 batch 지웁니다.
     *
     * <p>전파를 {@code REQUIRES_NEW} 로 둡니다. 정리는 짧고 독립적으로 커밋돼야 합니다.
     * 호출부의 긴 트랜잭션에 참여하면 지운 행의 잠금이 그 트랜잭션이 끝날 때까지 유지됩니다.
     *
     * @return 지운 건수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int clean() {
        Instant threshold = clock.instant().minus(retention);
        int deleted = outboxEventRepository.deletePublishedBefore(threshold, batchSize);

        if (deleted > 0) {
            outboxMetrics.recordCleaned(deleted);
            log.info("보관 기간을 지난 Outbox 레코드를 지웠습니다. count={}, threshold={}",
                deleted, threshold);
        }
        return deleted;
    }
}
