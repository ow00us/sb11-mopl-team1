package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.watchingsession.repository.ContentWatcherCountView;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WatcherCountRefreshServiceTest {

    private static final UUID CONTENT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTENT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CONTENT_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository =
            mock(WatchingSessionSnapshotRepository.class);
    private final ContentSearchRepository contentSearchRepository = mock(ContentSearchRepository.class);

    private final WatcherCountRefreshService service =
            new WatcherCountRefreshService(watchingSessionSnapshotRepository, contentSearchRepository);

    @Test
    @DisplayName("실시간 집계와 달라진 문서만 saveAll로 저장한다 (변화 없는 문서는 제외, 집계에 없는 문서는 0으로 리셋)")
    void refresh_savesOnlyChangedDocuments() {
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
        ArgumentCaptor<Iterable<ContentDocument>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(contentSearchRepository).saveAll(captor.capture());
        List<ContentDocument> saved = toList(captor.getValue());
        assertThat(saved).extracting(ContentDocument::getId)
                .containsExactlyInAnyOrder(CONTENT_A.toString(), CONTENT_C.toString());
        assertThat(saved).filteredOn(doc -> doc.getId().equals(CONTENT_A.toString()))
                .extracting(ContentDocument::getWatcherCount).containsExactly(5);
        assertThat(saved).filteredOn(doc -> doc.getId().equals(CONTENT_C.toString()))
                .extracting(ContentDocument::getWatcherCount).containsExactly(0);
    }

    @Test
    @DisplayName("변경된 문서가 없으면 saveAll을 호출하지 않는다")
    void refresh_noChanges_doesNotCallSaveAll() {
        ContentWatcherCountView viewA = watcherCountView(CONTENT_A, 5L);
        when(watchingSessionSnapshotRepository.countActiveWatchersGroupedByContent(any()))
                .thenReturn(List.of(viewA));
        ContentDocument docA = ContentDocument.builder().id(CONTENT_A.toString()).watcherCount(5).build();
        when(contentSearchRepository.findAll()).thenReturn(List.of(docA));

        service.refresh();

        verify(contentSearchRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("집계 조회가 예외를 던져도 refresh() 호출 자체는 예외 없이 끝나고 saveAll은 호출되지 않는다")
    void refresh_countQueryThrows_doesNotPropagateAndDoesNotSave() {
        when(watchingSessionSnapshotRepository.countActiveWatchersGroupedByContent(any()))
                .thenThrow(new RuntimeException("DB 연결 끊김"));

        assertThatCode(service::refresh).doesNotThrowAnyException();

        verify(contentSearchRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("ES 전체 조회가 예외를 던져도 refresh() 호출 자체는 예외 없이 끝나고 saveAll은 호출되지 않는다")
    void refresh_findAllThrows_doesNotPropagateAndDoesNotSave() {
        ContentWatcherCountView viewA = watcherCountView(CONTENT_A, 5L);
        when(watchingSessionSnapshotRepository.countActiveWatchersGroupedByContent(any()))
                .thenReturn(List.of(viewA));
        when(contentSearchRepository.findAll()).thenThrow(new RuntimeException("ES 연결 끊김"));

        assertThatCode(service::refresh).doesNotThrowAnyException();

        verify(contentSearchRepository, never()).saveAll(any());
    }

    private ContentWatcherCountView watcherCountView(UUID contentId, long watcherCount) {
        ContentWatcherCountView view = mock(ContentWatcherCountView.class);
        when(view.getContentId()).thenReturn(contentId);
        when(view.getWatcherCount()).thenReturn(watcherCount);
        return view;
    }

    private List<ContentDocument> toList(Iterable<ContentDocument> iterable) {
        return java.util.stream.StreamSupport.stream(iterable.spliterator(), false).toList();
    }
}