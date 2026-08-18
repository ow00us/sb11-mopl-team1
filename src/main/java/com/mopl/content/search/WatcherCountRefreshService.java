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
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WatcherCountRefreshService {

    private static final IndexCoordinates CONTENTS_INDEX = IndexCoordinates.of("contents");

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;
    private final ContentSearchRepository contentSearchRepository;
    private final ElasticsearchOperations elasticsearchOperations;

    @Async("contentSearchSyncExecutor")
    public void refresh() {
        try {
            Map<UUID, Long> liveCounts = watchingSessionSnapshotRepository
                    .countActiveWatchersGroupedByContent(Instant.now()).stream()
                    .collect(Collectors.toMap(
                            ContentWatcherCountView::getContentId,
                            ContentWatcherCountView::getWatcherCount));

            List<UpdateQuery> updateQueries = new ArrayList<>();
            for (ContentDocument doc : contentSearchRepository.findAll()) {
                int liveCount = liveCounts.getOrDefault(UUID.fromString(doc.getId()), 0L).intValue();
                if (!Objects.equals(doc.getWatcherCount(), liveCount)) {
                    updateQueries.add(UpdateQuery.builder(doc.getId())
                            .withDocument(Document.from(Map.of("watcherCount", liveCount)))
                            .build());
                }
            }

            if (!updateQueries.isEmpty()) {
                elasticsearchOperations.bulkUpdate(updateQueries, CONTENTS_INDEX);
            }
            log.info("watcherCount 리프레시 완료: 갱신 {}건", updateQueries.size());
        } catch (Exception e) {
            log.warn("watcherCount 리프레시 실패", e);
        }
    }
}
