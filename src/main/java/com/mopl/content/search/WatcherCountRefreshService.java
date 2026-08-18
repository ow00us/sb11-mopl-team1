package com.mopl.content.search;

import com.mopl.watchingsession.repository.ContentWatcherCountView;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatcherCountRefreshService {

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;
    private final ContentSearchRepository contentSearchRepository;

    @Async("contentSearchSyncExecutor")
    public void refresh() {
        try {
            Map<UUID, Long> liveCounts = watchingSessionSnapshotRepository
                    .countActiveWatchersGroupedByContent(Instant.now()).stream()
                    .collect(Collectors.toMap(
                            ContentWatcherCountView::getContentId,
                            ContentWatcherCountView::getWatcherCount));

            List<ContentDocument> toUpdate = new ArrayList<>();
            for (ContentDocument doc : contentSearchRepository.findAll()) {
                int liveCount = liveCounts.getOrDefault(UUID.fromString(doc.getId()), 0L).intValue();
                if (!Objects.equals(doc.getWatcherCount(), liveCount)) {
                    toUpdate.add(doc.toBuilder().watcherCount(liveCount).build());
                }
            }

            if (!toUpdate.isEmpty()) {
                contentSearchRepository.saveAll(toUpdate);
            }
            log.info("watcherCount 리프레시 완료: 갱신 {}건", toUpdate.size());
        } catch (Exception e) {
            log.warn("watcherCount 리프레시 실패", e);
        }
    }
}
