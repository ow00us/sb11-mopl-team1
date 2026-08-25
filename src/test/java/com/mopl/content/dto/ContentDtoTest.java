package com.mopl.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.ContentType;
import com.mopl.content.search.ContentDocument;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentDtoTest {

    @Test
    @DisplayName("from(ContentDocument)은 모든 필드를 정확히 변환한다")
    void from_document_mapsAllFieldsCorrectly() {
        UUID id = UUID.randomUUID();
        ContentDocument document = ContentDocument.builder()
                .id(id.toString())
                .contentId(id.toString())
                .title("제목")
                .description("설명")
                .type(ContentType.TV_SERIES.name())
                .tags(List.of("action", "sf"))
                .averageRating(3.5)
                .reviewCount(3)
                .watcherCount(7)
                .thumbnailUrl("https://example.com/thumb.jpg")
                .createdAt(LocalDateTime.now())
                .build();

        ContentDto dto = ContentDto.from(document);

        assertThat(dto.id()).isEqualTo(id);
        assertThat(dto.type()).isEqualTo(ContentType.TV_SERIES);
        assertThat(dto.title()).isEqualTo("제목");
        assertThat(dto.description()).isEqualTo("설명");
        assertThat(dto.thumbnailUrl()).isEqualTo("https://example.com/thumb.jpg");
        assertThat(dto.tags()).containsExactlyInAnyOrder("action", "sf");
        assertThat(dto.averageRating()).isEqualByComparingTo("3.5");
        assertThat(dto.averageRating().scale()).isEqualTo(1);
        assertThat(dto.reviewCount()).isEqualTo(3L);
        assertThat(dto.watcherCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("averageRating은 scale 1로 HALF_UP 반올림된다")
    void from_document_roundsAverageRatingHalfUp() {
        ContentDocument document = baseDocument().toBuilder()
                .averageRating(4.45)
                .build();

        ContentDto dto = ContentDto.from(document);

        assertThat(dto.averageRating()).isEqualByComparingTo(new BigDecimal("4.5"));
    }

    private ContentDocument baseDocument() {
        UUID id = UUID.randomUUID();
        return ContentDocument.builder()
                .id(id.toString())
                .contentId(id.toString())
                .title("제목")
                .description("설명")
                .type(ContentType.MOVIE.name())
                .tags(List.of())
                .averageRating(0.0)
                .reviewCount(0)
                .watcherCount(0)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
