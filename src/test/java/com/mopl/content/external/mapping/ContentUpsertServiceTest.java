package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ContentUpsertServiceTest {

    @Mock
    ContentRepository contentRepository;

    @Mock
    ContentInsertExecutor contentInsertExecutor;

    @InjectMocks
    ContentUpsertService contentUpsertService;

    private static final ExternalContentDraft DRAFT = new ExternalContentDraft(
            ContentType.MOVIE, ContentSource.TMDB, "1", "제목", "줄거리", "https://thumb.jpg",
            Set.of("Action"));

    @BeforeEach
    void setUp() {
        // 기본적으로 삭제된 콘텐츠가 없다고 가정한다. 삭제 케이스를 검증하는 테스트에서 개별적으로 덮어쓴다.
        lenient().when(contentRepository.findBySourceAndExternalIdIncludingDeleted(any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("기존 콘텐츠가 없으면 새로 생성해서 저장한다")
    void upsert_newContent_createsAndSaves() {
        when(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "1")).thenReturn(Optional.empty());
        when(contentInsertExecutor.insert(any(ExternalContentDraft.class)))
                .thenAnswer(invocation -> {
                    ExternalContentDraft draft = invocation.getArgument(0);
                    Content content = Content.builder()
                            .type(draft.type())
                            .source(draft.source())
                            .externalId(draft.externalId())
                            .title(draft.title())
                            .description(draft.description())
                            .thumbnailUrl(draft.thumbnailUrl())
                            .build();
                    draft.tags().forEach(content::addTag);
                    return content;
                });

        Content result = contentUpsertService.upsert(DRAFT).orElseThrow();

        ArgumentCaptor<ExternalContentDraft> captor = ArgumentCaptor.forClass(ExternalContentDraft.class);
        verify(contentInsertExecutor).insert(captor.capture());
        assertThat(captor.getValue().title()).isEqualTo("제목");
        assertThat(result.getTitle()).isEqualTo("제목");
        assertThat(result.getDescription()).isEqualTo("줄거리");
        assertThat(result.getThumbnailUrl()).isEqualTo("https://thumb.jpg");
        assertThat(result.getTags()).containsExactly("action");
    }

    @Test
    @DisplayName("기존 콘텐츠가 있으면 dirty checking에 맡기고 별도로 저장하지 않는다")
    void upsert_existingContent_updatesWithoutSaving() {
        Content existing = movie();
        when(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "1")).thenReturn(Optional.of(existing));

        Content result = contentUpsertService.upsert(DRAFT).orElseThrow();

        assertThat(result).isSameAs(existing);
        assertThat(existing.getTitle()).isEqualTo("제목");
        assertThat(existing.getDescription()).isEqualTo("줄거리");
        assertThat(existing.getThumbnailUrl()).isEqualTo("https://thumb.jpg");
        assertThat(existing.getTags()).containsExactly("action");
        verify(contentInsertExecutor, never()).insert(any());
    }

    @Test
    @DisplayName("삽입 시 DuplicateKeyException이 나면 재조회해서 기존 엔티티를 갱신한다")
    void upsert_insertThrowsDuplicateKeyException_recoversByRefetching() {
        Content existing = movie();
        when(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(contentInsertExecutor.insert(any(ExternalContentDraft.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        Content result = contentUpsertService.upsert(DRAFT).orElseThrow();

        assertThat(result).isSameAs(existing);
        assertThat(existing.getTitle()).isEqualTo("제목");
        verify(contentRepository, times(2)).findBySourceAndExternalId(ContentSource.TMDB, "1");
    }

    @Test
    @DisplayName("중복이 아닌 제약 위반은 재조회 없이 원본 예외를 그대로 전파한다")
    void upsert_insertThrowsNonDuplicateConstraintViolation_propagatesException() {
        when(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "1")).thenReturn(Optional.empty());
        SQLException checkViolation = new SQLException("check", "23514");
        when(contentInsertExecutor.insert(any(ExternalContentDraft.class)))
                .thenThrow(new DataIntegrityViolationException("check", checkViolation));

        assertThatThrownBy(() -> contentUpsertService.upsert(DRAFT))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(contentRepository, times(1)).findBySourceAndExternalId(ContentSource.TMDB, "1");
    }

    @Test
    @DisplayName("이미 삭제된 콘텐츠면 아무 것도 하지 않고 빈 Optional을 반환한다")
    void upsert_alreadyDeletedContent_skipsAndReturnsEmpty() {
        Content deleted = movie();
        ReflectionTestUtils.setField(deleted, "deletedAt", Instant.now());
        when(contentRepository.findBySourceAndExternalIdIncludingDeleted(ContentSource.TMDB.name(), "1"))
                .thenReturn(Optional.of(deleted));

        Optional<Content> result = contentUpsertService.upsert(DRAFT);

        assertThat(result).isEmpty();
        verify(contentRepository, never()).findBySourceAndExternalId(any(), any());
        verify(contentInsertExecutor, never()).insert(any());
    }

    private Content movie() {
        return Content.builder()
                .type(ContentType.MOVIE)
                .source(ContentSource.TMDB)
                .externalId("1")
                .title("기존 제목")
                .description("기존 줄거리")
                .build();
    }
}