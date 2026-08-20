package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;

class ContentSearchSyncListenerTest {

    private static final UUID CONTENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final ContentSearchKeyedExecutor contentSearchKeyedExecutor = mock(ContentSearchKeyedExecutor.class);
    private final ContentSearchSyncWorker contentSearchSyncWorker = mock(ContentSearchSyncWorker.class);
    private final ContentSearchRetryService contentSearchRetryService = mock(ContentSearchRetryService.class);

    private final ContentSearchSyncListener listener = new ContentSearchSyncListener(
            contentSearchKeyedExecutor, contentSearchSyncWorker, contentSearchRetryService);

    // ── 정상 위임 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("handleSync는 contentId로 레인에 위임하고, 위임된 작업은 worker.sync를 호출한다")
    void handleSync_delegatesToKeyedExecutor_andRunsWorkerSync() {
        listener.handleSync(new ContentSearchSyncEvent(CONTENT_ID));

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(contentSearchKeyedExecutor).execute(eq(CONTENT_ID), taskCaptor.capture());

        taskCaptor.getValue().run();

        verify(contentSearchSyncWorker).sync(CONTENT_ID);
        verifyNoMoreInteractions(contentSearchRetryService);
    }

    @Test
    @DisplayName("handleDelete는 contentId로 레인에 위임하고, 위임된 작업은 worker.delete를 호출한다")
    void handleDelete_delegatesToKeyedExecutor_andRunsWorkerDelete() {
        listener.handleDelete(new ContentSearchDeleteEvent(CONTENT_ID));

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(contentSearchKeyedExecutor).execute(eq(CONTENT_ID), taskCaptor.capture());

        taskCaptor.getValue().run();

        verify(contentSearchSyncWorker).delete(CONTENT_ID);
        verifyNoMoreInteractions(contentSearchRetryService);
    }

    // ── 레인 큐 포화(거부) ───────────────────────────────────────────────────

    @Test
    @DisplayName("handleSync 제출이 거부되면 재시도 대기열에 SYNC로 기록한다")
    void handleSync_rejected_recordsRetryAsSync() {
        doThrow(new TaskRejectedException("큐 포화")).when(contentSearchKeyedExecutor)
                .execute(eq(CONTENT_ID), any());

        listener.handleSync(new ContentSearchSyncEvent(CONTENT_ID));

        verify(contentSearchRetryService).recordRejected(CONTENT_ID, ContentSearchRetryEventType.SYNC);
    }

    @Test
    @DisplayName("handleDelete 제출이 거부되면 재시도 대기열에 DELETE로 기록한다")
    void handleDelete_rejected_recordsRetryAsDelete() {
        doThrow(new TaskRejectedException("큐 포화")).when(contentSearchKeyedExecutor)
                .execute(eq(CONTENT_ID), any());

        listener.handleDelete(new ContentSearchDeleteEvent(CONTENT_ID));

        verify(contentSearchRetryService).recordRejected(CONTENT_ID, ContentSearchRetryEventType.DELETE);
    }

    @Test
    @DisplayName("재시도 기록 자체가 실패해도 예외가 전파되지 않는다")
    void handleSync_rejectedAndRecordAlsoFails_doesNotPropagate() {
        doThrow(new TaskRejectedException("큐 포화")).when(contentSearchKeyedExecutor)
                .execute(eq(CONTENT_ID), any());
        doThrow(new RuntimeException("DB 연결 끊김")).when(contentSearchRetryService)
                .recordRejected(CONTENT_ID, ContentSearchRetryEventType.SYNC);

        assertThatCode(() -> listener.handleSync(new ContentSearchSyncEvent(CONTENT_ID)))
                .doesNotThrowAnyException();
    }
}
