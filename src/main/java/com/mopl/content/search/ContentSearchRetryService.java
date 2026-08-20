package com.mopl.content.search;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ES 색인 동기화 재시도 대기열을 기록하고 재적용합니다.
 */
@Slf4j
@Service
public class ContentSearchRetryService {

    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    private final ContentSearchRetryRepository contentSearchRetryRepository;
    private final ContentSearchSyncWorker contentSearchSyncWorker;
    private final int maxAttempts;

    public ContentSearchRetryService(
        ContentSearchRetryRepository contentSearchRetryRepository,
        ContentSearchSyncWorker contentSearchSyncWorker,
        @Value("${content-search.retry.max-attempts:5}") int maxAttempts
    ) {
        this.contentSearchRetryRepository = contentSearchRetryRepository;
        this.contentSearchSyncWorker = contentSearchSyncWorker;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 레인 큐가 가득 차 거부된 이벤트를 재시도 대기열에 남깁니다.
     *
     * <p>{@code ContentSearchSyncListener}의 AFTER_COMMIT 콜백에서 호출되므로, 별도
     * 트랜잭션(REQUIRES_NEW)으로 짧게 커밋합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(UUID contentId, ContentSearchRetryEventType eventType) {
        contentSearchRetryRepository.save(new ContentSearchRetry(contentId, eventType, Instant.now()));
    }

    /** 선점된 재시도 레코드 하나를 재적용합니다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(ContentSearchRetry retry) {
        boolean succeeded = switch (retry.getEventType()) {
            case SYNC -> contentSearchSyncWorker.sync(retry.getContentId());
            case DELETE -> contentSearchSyncWorker.delete(retry.getContentId());
        };

        if (succeeded) {
            retry.markCompleted();
        } else {
            retry.markFailedAttempt(maxAttempts, Instant.now().plus(RETRY_BACKOFF), "sync/delete 재시도 실패");
            log.warn("콘텐츠 검색 재시도 실패. contentId={}, eventType={}, attempts={}",
                    retry.getContentId(), retry.getEventType(), retry.getAttempts());
        }
        contentSearchRetryRepository.save(retry);
    }
}
