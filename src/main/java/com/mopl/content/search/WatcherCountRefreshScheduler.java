package com.mopl.content.search;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WatcherCountRefreshScheduler {

    private final WatcherCountRefreshService watcherCountRefreshService;

    @Scheduled(fixedDelayString = "${content-search.watcher-count-refresh-interval-millis:60000}")
    public void refreshWatcherCounts() {
        watcherCountRefreshService.refresh();
    }
}