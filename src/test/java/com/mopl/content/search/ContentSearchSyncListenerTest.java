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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ContentSearchSyncListenerTest {

    private static final UUID CONTENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final ContentRepository contentRepository = mock(ContentRepository.class);
    private final ContentSearchRepository contentSearchRepository = mock(ContentSearchRepository.class);
    private final ContentDocumentMapper contentDocumentMapper = mock(ContentDocumentMapper.class);

    private final ContentSearchSyncListener listener =
            new ContentSearchSyncListener(contentRepository, contentSearchRepository, contentDocumentMapper);

    // ── handleSync ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("콘텐츠가 존재하면 기존 문서를 조회해 매퍼로 변환한 뒤 저장한다")
    void handleSync_contentExists_savesMappedDocument() {
        Content content = content();
        ContentDocument existing = ContentDocument.builder().id(CONTENT_ID.toString()).build();
        ContentDocument mapped = ContentDocument.builder().id(CONTENT_ID.toString()).watcherCount(3).build();
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentSearchRepository.findById(CONTENT_ID.toString())).thenReturn(Optional.of(existing));
        when(contentDocumentMapper.toDocument(content, existing)).thenReturn(mapped);

        listener.handleSync(new ContentSearchSyncEvent(CONTENT_ID));

        verify(contentSearchRepository).save(mapped);
    }

    @Test
    @DisplayName("콘텐츠가 존재하지 않으면 검색 저장소와 매퍼를 전혀 호출하지 않는다")
    void handleSync_contentNotFound_doesNotTouchSearchRepositoryOrMapper() {
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.empty());

        listener.handleSync(new ContentSearchSyncEvent(CONTENT_ID));

        verifyNoInteractions(contentSearchRepository);
        verifyNoInteractions(contentDocumentMapper);
    }

    @Test
    @DisplayName("save()가 예외를 던져도 handleSync 호출 자체는 예외 없이 끝난다")
    void handleSync_saveThrows_doesNotPropagateException() {
        Content content = content();
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentSearchRepository.findById(CONTENT_ID.toString())).thenReturn(Optional.empty());
        when(contentDocumentMapper.toDocument(any(), any()))
                .thenReturn(ContentDocument.builder().id(CONTENT_ID.toString()).build());
        when(contentSearchRepository.save(any())).thenThrow(new RuntimeException("ES 연결 끊김"));

        assertThatCode(() -> listener.handleSync(new ContentSearchSyncEvent(CONTENT_ID)))
                .doesNotThrowAnyException();
    }

    // ── handleDelete ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("handleDelete는 contentId 문자열로 deleteById를 호출한다")
    void handleDelete_callsDeleteByIdWithContentIdString() {
        listener.handleDelete(new ContentSearchDeleteEvent(CONTENT_ID));

        verify(contentSearchRepository).deleteById(CONTENT_ID.toString());
    }

    @Test
    @DisplayName("deleteById가 예외를 던져도 예외가 전파되지 않는다")
    void handleDelete_deleteThrows_doesNotPropagateException() {
        doThrow(new RuntimeException("ES 연결 끊김")).when(contentSearchRepository).deleteById(any());

        assertThatCode(() -> listener.handleDelete(new ContentSearchDeleteEvent(CONTENT_ID)))
                .doesNotThrowAnyException();

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