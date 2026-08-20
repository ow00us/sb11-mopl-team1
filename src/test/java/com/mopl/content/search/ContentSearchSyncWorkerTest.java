package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.UpdateQuery;
import org.springframework.test.util.ReflectionTestUtils;

class ContentSearchSyncWorkerTest {

    private static final UUID CONTENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final ContentRepository contentRepository = mock(ContentRepository.class);
    private final ContentSearchRepository contentSearchRepository = mock(ContentSearchRepository.class);
    private final ContentDocumentMapper contentDocumentMapper = mock(ContentDocumentMapper.class);
    private final ElasticsearchOperations elasticsearchOperations = mock(ElasticsearchOperations.class);

    private final ContentSearchSyncWorker worker = new ContentSearchSyncWorker(
            contentRepository, contentSearchRepository, contentDocumentMapper, elasticsearchOperations);

    // ── sync ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ES에 문서가 아직 없으면 새 문서로 저장한다")
    void sync_documentNotYetIndexed_savesNewDocument() {
        Content content = content();
        ContentDocument newDocument = ContentDocument.builder().id(CONTENT_ID.toString()).watcherCount(0).build();
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentSearchRepository.existsById(CONTENT_ID.toString())).thenReturn(false);
        when(contentDocumentMapper.toNewDocument(content)).thenReturn(newDocument);

        worker.sync(CONTENT_ID);

        verify(contentSearchRepository).save(newDocument);
        verifyNoInteractions(elasticsearchOperations);
    }

    @Test
    @DisplayName("ES에 문서가 이미 있으면 watcherCount를 제외한 필드만 부분 업데이트한다")
    void sync_documentAlreadyIndexed_partiallyUpdatesFields() {
        Content content = content();
        Map<String, Object> updateFields = Map.of("title", "제목");
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentSearchRepository.existsById(CONTENT_ID.toString())).thenReturn(true);
        when(contentDocumentMapper.toUpdateFields(content)).thenReturn(updateFields);

        worker.sync(CONTENT_ID);

        verify(elasticsearchOperations).update(any(UpdateQuery.class), any(IndexCoordinates.class));
        verify(contentSearchRepository, never()).save(any());
    }

    @Test
    @DisplayName("콘텐츠가 존재하지 않으면 검색 저장소·매퍼·ES 무엇도 호출하지 않는다")
    void sync_contentNotFound_doesNotTouchSearchRepositoryMapperOrEs() {
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.empty());

        worker.sync(CONTENT_ID);

        verifyNoInteractions(contentSearchRepository);
        verifyNoInteractions(contentDocumentMapper);
        verifyNoInteractions(elasticsearchOperations);
    }

    @Test
    @DisplayName("부분 업데이트가 예외를 던져도 sync 호출 자체는 예외 없이 끝난다")
    void sync_updateThrows_doesNotPropagateException() {
        Content content = content();
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentSearchRepository.existsById(CONTENT_ID.toString())).thenReturn(true);
        when(contentDocumentMapper.toUpdateFields(content)).thenReturn(Map.of("title", "제목"));
        when(elasticsearchOperations.update(any(UpdateQuery.class), any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("ES 연결 끊김"));

        assertThatCode(() -> worker.sync(CONTENT_ID)).doesNotThrowAnyException();
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete는 contentId 문자열로 deleteById를 호출한다")
    void delete_callsDeleteByIdWithContentIdString() {
        worker.delete(CONTENT_ID);

        verify(contentSearchRepository).deleteById(CONTENT_ID.toString());
    }

    @Test
    @DisplayName("deleteById가 예외를 던져도 예외가 전파되지 않는다")
    void delete_deleteThrows_doesNotPropagateException() {
        doThrow(new RuntimeException("ES 연결 끊김")).when(contentSearchRepository).deleteById(any());

        assertThatCode(() -> worker.delete(CONTENT_ID)).doesNotThrowAnyException();

        verify(contentSearchRepository, never()).save(any());
    }

    private Content content() {
        Content content = Content.builder()
                .type(ContentType.MOVIE)
                .title("제목")
                .description("설명")
                .build();
        ReflectionTestUtils.setField(content, "id", CONTENT_ID);
        return content;
    }
}
