package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ContentUpsertServiceTest {

    @Mock
    ContentRepository contentRepository;

    @InjectMocks
    ContentUpsertService contentUpsertService;

    private static final ExternalContentDraft DRAFT = new ExternalContentDraft(
            ContentType.MOVIE, ContentSource.TMDB, "1", "제목", "줄거리", "https://thumb.jpg",
            Set.of("Action"));

    @Test
    @DisplayName("기존 콘텐츠가 없으면 새로 생성해서 저장한다")
    void upsert_newContent_createsAndSaves() {
        when(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "1")).thenReturn(Optional.empty());
        when(contentRepository.saveAndFlush(any(Content.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Content result = contentUpsertService.upsert(DRAFT);

        ArgumentCaptor<Content> captor = ArgumentCaptor.forClass(Content.class);
        verify(contentRepository).saveAndFlush(captor.capture());
        Content saved = captor.getValue();
        assertThat(saved.getTitle()).isEqualTo("제목");
        assertThat(saved.getDescription()).isEqualTo("줄거리");
        assertThat(saved.getThumbnailUrl()).isEqualTo("https://thumb.jpg");
        assertThat(saved.getTags()).containsExactly("action");
        assertThat(result).isSameAs(saved);
    }

    @Test
    @DisplayName("기존 콘텐츠가 있으면 dirty checking에 맡기고 별도로 저장하지 않는다")
    void upsert_existingContent_updatesWithoutSaving() {
        Content existing = movie();
        when(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "1")).thenReturn(Optional.of(existing));

        Content result = contentUpsertService.upsert(DRAFT);

        assertThat(result).isSameAs(existing);
        assertThat(existing.getTitle()).isEqualTo("제목");
        assertThat(existing.getDescription()).isEqualTo("줄거리");
        assertThat(existing.getThumbnailUrl()).isEqualTo("https://thumb.jpg");
        assertThat(existing.getTags()).containsExactly("action");
        verify(contentRepository, never()).save(any());
        verify(contentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("저장 시 DuplicateKeyException이 나면 재조회해서 기존 엔티티를 갱신한다")
    void upsert_saveThrowsDuplicateKeyException_recoversByRefetching() {
        Content existing = movie();
        when(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(contentRepository.saveAndFlush(any(Content.class))).thenThrow(new DuplicateKeyException("dup"));

        Content result = contentUpsertService.upsert(DRAFT);

        assertThat(result).isSameAs(existing);
        assertThat(existing.getTitle()).isEqualTo("제목");
        verify(contentRepository, times(2)).findBySourceAndExternalId(ContentSource.TMDB, "1");
    }

    @Test
    @DisplayName("중복이 아닌 제약 위반은 재조회 없이 원본 예외를 그대로 전파한다")
    void upsert_saveThrowsNonDuplicateConstraintViolation_propagatesException() {
        when(contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "1")).thenReturn(Optional.empty());
        SQLException checkViolation = new SQLException("check", "23514");
        when(contentRepository.saveAndFlush(any(Content.class)))
                .thenThrow(new DataIntegrityViolationException("check", checkViolation));

        assertThatThrownBy(() -> contentUpsertService.upsert(DRAFT))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(contentRepository, times(1)).findBySourceAndExternalId(ContentSource.TMDB, "1");
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