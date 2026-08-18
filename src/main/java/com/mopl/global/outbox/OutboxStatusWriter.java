package com.mopl.global.outbox;

import java.time.Instant;
import java.util.UUID;
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
@Component
public class OutboxStatusWriter {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxStatusWriter(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPublished(UUID id, Instant publishedAt) {
        outboxEventRepository.findById(id)
            .ifPresent(event -> event.markPublished(publishedAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAttemptFailed(UUID id, String lastError) {
        outboxEventRepository.findById(id)
            .ifPresent(event -> event.markAttemptFailed(lastError));
    }
}
