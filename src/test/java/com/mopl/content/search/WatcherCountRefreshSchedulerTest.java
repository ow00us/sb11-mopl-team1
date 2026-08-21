package com.mopl.content.search;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WatcherCountRefreshSchedulerTest {

    private final WatcherCountRefreshService watcherCountRefreshService = mock(WatcherCountRefreshService.class);

    private final WatcherCountRefreshScheduler scheduler =
            new WatcherCountRefreshScheduler(watcherCountRefreshService);

    @Test
    @DisplayName("refreshWatcherCounts()는 WatcherCountRefreshService.refresh()를 호출한다")
    void refreshWatcherCounts_callsServiceRefresh() {
        scheduler.refreshWatcherCounts();

        verify(watcherCountRefreshService).refresh();
    }
}
