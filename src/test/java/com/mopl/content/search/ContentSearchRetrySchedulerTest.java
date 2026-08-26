package com.mopl.content.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentSearchRetrySchedulerTest {

    private final ContentSearchRetryClaimer contentSearchRetryClaimer = mock(ContentSearchRetryClaimer.class);
    private final ContentSearchRetryService contentSearchRetryService = mock(ContentSearchRetryService.class);

    private final ContentSearchRetryScheduler scheduler =
            new ContentSearchRetryScheduler(contentSearchRetryClaimer, contentSearchRetryService);

    @Test
    @DisplayName("선점한 재시도 레코드마다 process를 호출한다")
    void retryPendingSync_processesEachClaimedRetry() {
        ContentSearchRetry first =
                new ContentSearchRetry(UUID.randomUUID(), ContentSearchRetryEventType.SYNC, Instant.now());
        ContentSearchRetry second =
                new ContentSearchRetry(UUID.randomUUID(), ContentSearchRetryEventType.DELETE, Instant.now());
        when(contentSearchRetryClaimer.claim(anyInt(), any())).thenReturn(List.of(first, second));

        scheduler.retryPendingSync();

        verify(contentSearchRetryService).process(first);
        verify(contentSearchRetryService).process(second);
    }

    @Test
    @DisplayName("선점된 레코드가 없으면 process를 호출하지 않는다")
    void retryPendingSync_noClaimedRetries_doesNotCallProcess() {
        when(contentSearchRetryClaimer.claim(anyInt(), any())).thenReturn(List.of());

        scheduler.retryPendingSync();

        verify(contentSearchRetryService, never()).process(any());
    }
}
