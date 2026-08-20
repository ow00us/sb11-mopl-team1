package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.watchingsession.repository.ContentWatcherCountView;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.test.util.ReflectionTestUtils;

class WatcherCountRefreshServiceTest {

    private static final UUID CONTENT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTENT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CONTENT_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository =
            mock(WatchingSessionSnapshotRepository.class);
    private final ContentSearchRepository contentSearchRepository = mock(ContentSearchRepository.class);
    private final ElasticsearchOperations elasticsearchOperations = mock(ElasticsearchOperations.class);

    private final WatcherCountRefreshService service = new WatcherCountRefreshService(
            watchingSessionSnapshotRepository, contentSearchRepository, elasticsearchOperations);

    @Test
    @DisplayName("실시간 집계와 달라진 문서만 부분 업데이트한다 (변화 없는 문서는 제외, 집계에 없는 문서는 0으로 리셋)")
    void refresh_updatesOnlyChangedDocuments() {
        ContentWatcherCountView viewA = watcherCountView(CONTENT_A, 5L);
        ContentWatcherCountView viewB = watcherCountView(CONTENT_B, 3L);
        when(watchingSessionSnapshotRepository.countActiveWatchersGroupedByContent(any()))
                .thenReturn(List.of(viewA, viewB));
        ContentDocument docA = ContentDocument.builder().id(CONTENT_A.toString()).watcherCount(2).build();
        ContentDocument docB = ContentDocument.builder().id(CONTENT_B.toString()).watcherCount(3).build();
        ContentDocument docC = ContentDocument.builder().id(CONTENT_C.toString()).watcherCount(4).build();
        when(contentSearchRepository.findAll()).thenReturn(List.of(docA, docB, docC));

        service.refresh();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UpdateQuery>> captor = ArgumentCaptor.forClass(List.class);
        verify(elasticsearchOperations).bulkUpdate(captor.capture(), any(IndexCoordinates.class));
        List<UpdateQuery> queries = captor.getValue();
        assertThat(queries).extracting(UpdateQuery::getId)
                .containsExactlyInAnyOrder(CONTENT_A.toString(), CONTENT_C.toString());
    }

    @Test
    @DisplayName("변경된 문서가 없으면 bulkUpdate를 호출하지 않는다")
    void refresh_noChanges_doesNotCallBulkUpdate() {
        ContentWatcherCountView viewA = watcherCountView(CONTENT_A, 5L);
        when(watchingSessionSnapshotRepository.countActiveWatchersGroupedByContent(any()))
                .thenReturn(List.of(viewA));
        ContentDocument docA = ContentDocument.builder().id(CONTENT_A.toString()).watcherCount(5).build();
        when(contentSearchRepository.findAll()).thenReturn(List.of(docA));

        service.refresh();

        verify(elasticsearchOperations, never()).bulkUpdate(any(), any(IndexCoordinates.class));
    }

    @Test
    @DisplayName("집계 조회가 예외를 던져도 refresh() 호출 자체는 예외 없이 끝나고 bulkUpdate는 호출되지 않는다")
    void refresh_countQueryThrows_doesNotPropagateAndDoesNotUpdate() {
        when(watchingSessionSnapshotRepository.countActiveWatchersGroupedByContent(any()))
                .thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(service::refresh).doesNotThrowAnyException();

        verify(elasticsearchOperations, never()).bulkUpdate(any(), any(IndexCoordinates.class));
    }

    @Test
    @DisplayName("ES 전체 조회가 예외를 던져도 refresh() 호출 자체는 예외 없이 끝나고 bulkUpdate는 호출되지 않는다")
    void refresh_findAllThrows_doesNotPropagateAndDoesNotUpdate() {
        ContentWatcherCountView viewA = watcherCountView(CONTENT_A, 5L);
        when(watchingSessionSnapshotRepository.countActiveWatchersGroupedByContent(any()))
                .thenReturn(List.of(viewA));
        when(contentSearchRepository.findAll()).thenThrow(new RuntimeException("ES 연결 끊김"));

        assertThatCode(service::refresh).doesNotThrowAnyException();

        verify(elasticsearchOperations, never()).bulkUpdate(any(), any(IndexCoordinates.class));
    }

    @Test
    @DisplayName("이미 리프레시가 진행 중이면 이번 호출은 아무 것도 하지 않고 건너뛴다")
    void refresh_alreadyRunning_skipsWithoutTouchingDependencies() {
        ReflectionTestUtils.setField(service, "isRefreshing", new AtomicBoolean(true));

        assertThatCode(service::refresh).doesNotThrowAnyException();

        verifyNoInteractions(watchingSessionSnapshotRepository);
        verifyNoInteractions(contentSearchRepository);
        verifyNoInteractions(elasticsearchOperations);
    }

    private ContentWatcherCountView watcherCountView(UUID contentId, long watcherCount) {
        ContentWatcherCountView view = mock(ContentWatcherCountView.class);
        when(view.getContentId()).thenReturn(contentId);
        when(view.getWatcherCount()).thenReturn(watcherCount);
        return view;
    }
}
