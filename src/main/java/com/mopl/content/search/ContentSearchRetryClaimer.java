package com.mopl.content.search;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재시도 대기 중인 {@link ContentSearchRetry} 레코드를 선점합니다.
 *
 * <p>선점을 짧고 독립적인 트랜잭션(REQUIRES_NEW)으로 커밋해, 여러 인스턴스가 동시에 재시도를
 * 돌려도 같은 레코드를 두 번 가져가지 않게 합니다. {@code OutboxClaimer}와 같은 이유로 별도
 * 빈으로 둡니다 — 호출부(ContentSearchRetryScheduler)가 다른 빈이어야 이 트랜잭션 경계가
 * 실제로 적용됩니다.
 */
@Component
public class ContentSearchRetryClaimer {

    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);

    private final ContentSearchRetryRepository contentSearchRetryRepository;
    private final String owner = UUID.randomUUID().toString();

    public ContentSearchRetryClaimer(ContentSearchRetryRepository contentSearchRetryRepository) {
        this.contentSearchRetryRepository = contentSearchRetryRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ContentSearchRetry> claim(int batchSize, Instant now) {
        List<UUID> ids = contentSearchRetryRepository.findClaimableIds(now, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        contentSearchRetryRepository.claimByIds(owner, now.plus(LEASE_DURATION), now, ids);
        return contentSearchRetryRepository.findByIdInOrderByNextAttemptAtAscIdAsc(ids);
    }
}
