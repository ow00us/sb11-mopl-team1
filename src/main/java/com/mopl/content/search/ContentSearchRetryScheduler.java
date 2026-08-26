package com.mopl.content.search;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentSearchRetryScheduler {

    private static final int CLAIM_BATCH_SIZE = 50;

    private final ContentSearchRetryClaimer contentSearchRetryClaimer;
    private final ContentSearchRetryService contentSearchRetryService;

    @Scheduled(fixedDelayString = "${content-search.retry.interval-millis:60000}")
    public void retryPendingSync() {
        List<ContentSearchRetry> claimed = contentSearchRetryClaimer.claim(CLAIM_BATCH_SIZE, Instant.now());
        for (ContentSearchRetry retry : claimed) {
            contentSearchRetryService.process(retry);
        }
    }
}
