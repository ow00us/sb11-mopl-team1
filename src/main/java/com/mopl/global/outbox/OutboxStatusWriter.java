package com.mopl.global.outbox;

import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발행 결과를 Outbox 에 반영합니다.
 *
 * <p>레코드마다 독립 트랜잭션으로 커밋합니다. batch 안의 한 건이 실패해도 앞서 발행에
 * 성공한 건들의 결과가 함께 롤백되면, 이미 broker 로 나간 이벤트가 발행 대기로 남아 그대로
 * 다시 발행됩니다.
 */
@Slf4j
@Component
public class OutboxStatusWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxRetryPolicy outboxRetryPolicy;
    private final OutboxMetrics outboxMetrics;

    public OutboxStatusWriter(
        OutboxEventRepository outboxEventRepository,
        OutboxRetryPolicy outboxRetryPolicy,
        OutboxMetrics outboxMetrics
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxRetryPolicy = outboxRetryPolicy;
        this.outboxMetrics = outboxMetrics;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID id, Instant publishedAt) {
        outboxEventRepository.findById(id).ifPresent(event -> {
            event.markPublished(publishedAt);
            outboxMetrics.recordPublished();
        });
    }

    /**
     * 발행 실패를 반영합니다.
     *
     * <p>남은 시도 횟수가 있으면 backoff 만큼 미뤄 다시 발행 대기로 두고, 없으면 최종 실패로
     * 남깁니다. 최종 실패는 자동 relay 대상에서 빠지므로 사람이 개입할 때까지 같은 실패를
     * 반복하지 않습니다.
     *
     * <p>실패 원인으로 재시도 여부를 가르지 않습니다. 토픽을 정할 수 없는 이벤트처럼 다시
     * 해도 결과가 같아 보이는 실패도 그렇습니다. 새 이벤트를 추가하는 배포가 진행되는 동안은
     * 아직 교체되지 않은 인스턴스가 그 타입을 모르는데, 그때 곧바로 최종 실패로 보내면 배포만
     * 끝나면 발행됐을 이벤트가 사람 손을 기다리게 됩니다. 몇 번 더 시도하는 비용이 그보다
     * 작습니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAttemptFailed(UUID id, String lastError, Instant now) {
        outboxEventRepository.findById(id).ifPresent(event -> {
            int attempts = event.getAttempts() + 1;
            if (outboxRetryPolicy.isExhausted(attempts)) {
                event.markFailed(lastError);
                outboxMetrics.recordExhausted();
                log.error("Outbox 발행을 {}회 실패해 최종 실패로 남깁니다. eventId={}, type={}, lastError={}",
                    attempts, event.getEventId(), event.getType(), lastError);
                return;
            }
            event.markAttemptFailed(lastError, outboxRetryPolicy.nextAttemptAt(attempts, now));
            outboxMetrics.recordRetried();
        });
    }
}
