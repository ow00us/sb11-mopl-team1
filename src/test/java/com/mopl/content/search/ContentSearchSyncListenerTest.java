package com.mopl.content.search;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ContentSearchSyncListenerTest {

    private static final UUID CONTENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final ContentSearchKeyedExecutor contentSearchKeyedExecutor = mock(ContentSearchKeyedExecutor.class);
    private final ContentSearchSyncWorker contentSearchSyncWorker = mock(ContentSearchSyncWorker.class);

    private final ContentSearchSyncListener listener =
            new ContentSearchSyncListener(contentSearchKeyedExecutor, contentSearchSyncWorker);

    @Test
    @DisplayName("handleSync는 contentId로 레인에 위임하고, 위임된 작업은 worker.sync를 호출한다")
    void handleSync_delegatesToKeyedExecutor_andRunsWorkerSync() {
        listener.handleSync(new ContentSearchSyncEvent(CONTENT_ID));

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(contentSearchKeyedExecutor).execute(eq(CONTENT_ID), taskCaptor.capture());
        verifyNoMoreInteractions(contentSearchKeyedExecutor);

        taskCaptor.getValue().run();

        verify(contentSearchSyncWorker).sync(CONTENT_ID);
    }

    @Test
    @DisplayName("handleDelete는 contentId로 레인에 위임하고, 위임된 작업은 worker.delete를 호출한다")
    void handleDelete_delegatesToKeyedExecutor_andRunsWorkerDelete() {
        listener.handleDelete(new ContentSearchDeleteEvent(CONTENT_ID));

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(contentSearchKeyedExecutor).execute(eq(CONTENT_ID), taskCaptor.capture());
        verifyNoMoreInteractions(contentSearchKeyedExecutor);

        taskCaptor.getValue().run();

        verify(contentSearchSyncWorker).delete(CONTENT_ID);
    }
}
