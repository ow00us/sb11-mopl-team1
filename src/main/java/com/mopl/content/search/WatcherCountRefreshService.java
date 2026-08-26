package com.mopl.content.search;

import com.mopl.watchingsession.repository.ContentWatcherCountView;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // 이전 refresh()가 아직 끝나지 않았는데 다음 스케줄이 또 실행되는 걸 막는다.
    // 단일 인스턴스 기준 가드라서, 나중에 여러 인스턴스로 늘어나면 분산 락으로 바꿔야 한다.
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);

    @Async("contentSearchSyncExecutor")
    public void refresh() {
        if (!isRefreshing.compareAndSet(false, true)) {
            log.info("watcherCount 리프레시가 이미 진행 중이라 이번 실행은 건너뜁니다.");
            return;
        }
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
        } finally {
            isRefreshing.set(false);
        }
    }
}
