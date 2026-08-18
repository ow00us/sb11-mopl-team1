package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ContentDocumentMapperTest {

    private final ContentDocumentMapper mapper = new ContentDocumentMapper();

    @Test
    @DisplayName("existing 문서가 없으면 watcherCount는 0으로 채워진다")
    void toDocument_withoutExisting_defaultsWatcherCountToZero() {
        Content content = content();

        ContentDocument document = mapper.toDocument(content, null);

        assertThat(document.getWatcherCount()).isZero();
    }

    @Test
    @DisplayName("existing 문서가 있으면 watcherCount는 그대로 보존하고 나머지 필드는 content 기준으로 갱신한다")
    void toDocument_withExisting_preservesWatcherCountAndUpdatesOtherFields() {
        Content content = content();
        ContentDocument existing = ContentDocument.builder()
                .id(content.getId().toString())
                .title("이전 제목")
                .description("이전 설명")
                .watcherCount(42)
                .build();

        ContentDocument document = mapper.toDocument(content, existing);

        assertThat(document.getWatcherCount()).isEqualTo(42);
        assertThat(document.getTitle()).isEqualTo("제목");
        assertThat(document.getDescription()).isEqualTo("설명");
    }

    @Test
    @DisplayName("Content 엔티티의 필드가 ContentDocument에 정확히 매핑된다")
    void toDocument_mapsAllFieldsFromContent() {
        Content content = content();

        ContentDocument document = mapper.toDocument(content, null);

        assertThat(document.getId()).isEqualTo(content.getId().toString());
        assertThat(document.getTitle()).isEqualTo(content.getTitle());
        assertThat(document.getDescription()).isEqualTo(content.getDescription());
        assertThat(document.getType()).isEqualTo(content.getType().name());
        assertThat(document.getTags()).containsExactlyInAnyOrderElementsOf(content.getTags());
        assertThat(document.getAverageRating()).isEqualTo(content.getAverageRating().doubleValue());
        assertThat(document.getCreatedAt())
                .isEqualTo(LocalDateTime.ofInstant(content.getCreatedAt(), ZoneId.systemDefault()));
    }

    private Content content() {
        Content content = Content.builder()
                .type(ContentType.MOVIE)
                .title("제목")
                .description("설명")
                .build();
        content.addTag("action");
        content.addTag("sf");
        ReflectionTestUtils.setField(content, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(content, "createdAt", Instant.now());
        ReflectionTestUtils.setField(content, "averageRating", new BigDecimal("4.5"));
        return content;
    }
}