package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContentSearchRetryServiceTest {

    private static final UUID CONTENT_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final int MAX_ATTEMPTS = 3;

    private final ContentSearchRetryRepository contentSearchRetryRepository = mock(ContentSearchRetryRepository.class);
    private final ContentSearchSyncWorker contentSearchSyncWorker = mock(ContentSearchSyncWorker.class);

    private final ContentSearchRetryService service =
            new ContentSearchRetryService(contentSearchRetryRepository, contentSearchSyncWorker, MAX_ATTEMPTS);

    // ── recordRejected ──────────────────────────────────────────────────────

    @Test
    @DisplayName("recordRejected는 PENDING 상태의 새 재시도 레코드를 저장한다")
    void recordRejected_savesNewPendingRetry() {
        service.recordRejected(CONTENT_ID, ContentSearchRetryEventType.SYNC);

        ArgumentCaptor<ContentSearchRetry> captor = ArgumentCaptor.forClass(ContentSearchRetry.class);
        verify(contentSearchRetryRepository).save(captor.capture());
        ContentSearchRetry saved = captor.getValue();
        assertThat(saved.getContentId()).isEqualTo(CONTENT_ID);
        assertThat(saved.getEventType()).isEqualTo(ContentSearchRetryEventType.SYNC);
        assertThat(saved.getStatus()).isEqualTo(ContentSearchRetryStatus.PENDING);
        assertThat(saved.getAttempts()).isZero();
    }

    // ── process ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SYNC 재시도는 worker.sync를 호출하고, 성공하면 COMPLETED로 저장한다")
    void process_syncSucceeds_marksCompletedAndSaves() {
        when(contentSearchSyncWorker.sync(CONTENT_ID)).thenReturn(true);
        ContentSearchRetry retry = new ContentSearchRetry(CONTENT_ID, ContentSearchRetryEventType.SYNC, Instant.now());

        service.process(retry);

        verify(contentSearchSyncWorker).sync(CONTENT_ID);
        verify(contentSearchSyncWorker, never()).delete(any());
        assertThat(retry.getStatus()).isEqualTo(ContentSearchRetryStatus.COMPLETED);
        verify(contentSearchRetryRepository).save(retry);
    }

    @Test
    @DisplayName("DELETE 재시도는 worker.delete를 호출한다")
    void process_deleteEvent_callsWorkerDelete() {
        when(contentSearchSyncWorker.delete(CONTENT_ID)).thenReturn(true);
        ContentSearchRetry retry = new ContentSearchRetry(CONTENT_ID, ContentSearchRetryEventType.DELETE, Instant.now());

        service.process(retry);

        verify(contentSearchSyncWorker).delete(CONTENT_ID);
        verify(contentSearchSyncWorker, never()).sync(any());
        assertThat(retry.getStatus()).isEqualTo(ContentSearchRetryStatus.COMPLETED);
    }

    @Test
    @DisplayName("실패하면 attempts를 늘리고, 최대 시도 횟수 미만이면 PENDING으로 남긴다")
    void process_fails_belowMaxAttempts_staysPending() {
        when(contentSearchSyncWorker.sync(CONTENT_ID)).thenReturn(false);
        ContentSearchRetry retry = new ContentSearchRetry(CONTENT_ID, ContentSearchRetryEventType.SYNC, Instant.now());

        service.process(retry);

        assertThat(retry.getAttempts()).isEqualTo(1);
        assertThat(retry.getStatus()).isEqualTo(ContentSearchRetryStatus.PENDING);
    }

    @Test
    @DisplayName("최대 시도 횟수에 도달하면 FAILED로 고정한다")
    void process_fails_reachesMaxAttempts_marksFailed() {
        when(contentSearchSyncWorker.sync(CONTENT_ID)).thenReturn(false);
        ContentSearchRetry retry = new ContentSearchRetry(CONTENT_ID, ContentSearchRetryEventType.SYNC, Instant.now());

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            service.process(retry);
        }

        assertThat(retry.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(retry.getStatus()).isEqualTo(ContentSearchRetryStatus.FAILED);
    }
}
