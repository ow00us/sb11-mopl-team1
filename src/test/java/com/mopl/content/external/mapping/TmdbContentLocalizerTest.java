package com.mopl.content.external.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.ContentSearchSyncEvent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TmdbContentLocalizerTest {

    @Mock
    ContentRepository contentRepository;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    TmdbContentLocalizer tmdbContentLocalizer;

    private Content taggedMovie() {
        Content content = Content.builder()
                .type(ContentType.MOVIE)
                .source(ContentSource.TMDB)
                .externalId("603")
                .title("The Matrix")
                .description("A hacker discovers reality is a simulation.")
                .build();
        content.addTag("Action");
        content.addTag("SF");
        ReflectionTestUtils.setField(content, "id", UUID.randomUUID());
        return content;
    }

    @Test
    @DisplayName("localize는 기존 콘텐츠의 제목·설명만 갱신하고 태그는 그대로 유지한다")
    void localize_existingContent_updatesTitleAndOverviewButKeepsTags() {
        // given
        Content content = taggedMovie();
        when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));

        // when
        tmdbContentLocalizer.localize(content.getId(), "매트릭스", "해커가 현실이 시뮬레이션임을 깨닫는다.");

        // then
        assertThat(content.getTitle()).isEqualTo("매트릭스");
        assertThat(content.getDescription()).isEqualTo("해커가 현실이 시뮬레이션임을 깨닫는다.");
        assertThat(content.getTags()).containsExactlyInAnyOrder("Action", "SF");
    }

    @Test
    @DisplayName("localize는 갱신 후 검색 색인 동기화 이벤트를 대상 콘텐츠 ID로 발행한다")
    void localize_existingContent_publishesSearchSyncEvent() {
        // given
        Content content = taggedMovie();
        when(contentRepository.findById(content.getId())).thenReturn(Optional.of(content));

        // when
        tmdbContentLocalizer.localize(content.getId(), "매트릭스", "줄거리");

        // then
        ArgumentCaptor<ContentSearchSyncEvent> captor = ArgumentCaptor.forClass(ContentSearchSyncEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().contentId()).isEqualTo(content.getId());
    }

    @Test
    @DisplayName("localize는 콘텐츠가 없으면 예외 없이 조용히 리턴하고 이벤트를 발행하지 않는다")
    void localize_contentNotFound_doesNothingAndDoesNotPublishEvent() {
        // given
        UUID contentId = UUID.randomUUID();
        when(contentRepository.findById(contentId)).thenReturn(Optional.empty());

        // when
        tmdbContentLocalizer.localize(contentId, "매트릭스", "줄거리");

        // then
        verify(eventPublisher, never()).publishEvent(any());
    }
}
