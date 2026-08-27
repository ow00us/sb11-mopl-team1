package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ContentDocumentMapperTest {

    private final ContentDocumentMapper mapper = new ContentDocumentMapper();

    // ── toNewDocument ────────────────────────────────────────────────────────

    @Test
    @DisplayName("toNewDocument()는 watcherCount를 0으로 채운다")
    void toNewDocument_defaultsWatcherCountToZero() {
        ContentDocument document = mapper.toNewDocument(content());

        assertThat(document.getWatcherCount()).isZero();
    }

    @Test
    @DisplayName("toNewDocument()는 Content 엔티티의 필드를 ContentDocument에 정확히 매핑한다")
    void toNewDocument_mapsAllFieldsFromContent() {
        Content content = content();

        ContentDocument document = mapper.toNewDocument(content);

        assertThat(document.getId()).isEqualTo(content.getId().toString());
        assertThat(document.getTitle()).isEqualTo(content.getTitle());
        assertThat(document.getDescription()).isEqualTo(content.getDescription());
        assertThat(document.getType()).isEqualTo(content.getType().name());
        assertThat(document.getTags()).containsExactlyInAnyOrderElementsOf(content.getNormalizedTags());
        assertThat(document.getDisplayTags()).containsExactlyInAnyOrderElementsOf(content.getTags());
        assertThat(document.getAverageRating()).isEqualTo(content.getAverageRating().doubleValue());
        assertThat(document.getCreatedAt())
                .isEqualTo(LocalDateTime.ofInstant(content.getCreatedAt(), ZoneOffset.UTC));
        assertThat(document.getReviewCount()).isEqualTo(content.getReviewCount().intValue());
        assertThat(document.getContentId()).isEqualTo(content.getId().toString());
        assertThat(document.getThumbnailUrl()).isEqualTo(content.getThumbnailUrl());
        assertThat(document.getCreatedAtEpochMicros()).isEqualTo(expectedEpochMicros(content.getCreatedAt()));
    }

    // ── toUpdateFields ───────────────────────────────────────────────────────

    @Test
    @DisplayName("toUpdateFields()는 watcherCount 키를 절대 포함하지 않는다")
    void toUpdateFields_neverIncludesWatcherCount() {
        Map<String, Object> fields = mapper.toUpdateFields(content());

        assertThat(fields).doesNotContainKey("watcherCount");
    }

    @Test
    @DisplayName("toUpdateFields()는 watcherCount를 제외한 나머지 필드를 정확히 담는다(contentId 포함)")
    void toUpdateFields_mapsRemainingFieldsFromContent() {
        Content content = content();

        Map<String, Object> fields = mapper.toUpdateFields(content);

        assertThat(fields.get("title")).isEqualTo(content.getTitle());
        assertThat(fields.get("description")).isEqualTo(content.getDescription());
        assertThat(fields.get("type")).isEqualTo(content.getType().name());
        @SuppressWarnings("unchecked")
        java.util.List<String> tags = (java.util.List<String>) fields.get("tags");
        assertThat(tags).containsExactlyInAnyOrderElementsOf(content.getNormalizedTags());
        @SuppressWarnings("unchecked")
        java.util.List<String> displayTags = (java.util.List<String>) fields.get("displayTags");
        assertThat(displayTags).containsExactlyInAnyOrderElementsOf(content.getTags());
        assertThat(fields.get("averageRating")).isEqualTo(content.getAverageRating().doubleValue());
        assertThat(fields.get("reviewCount")).isEqualTo(content.getReviewCount().intValue());
        assertThat(fields.get("thumbnailUrl")).isEqualTo(content.getThumbnailUrl());
        assertThat(fields.get("contentId")).isEqualTo(content.getId().toString());
        assertThat(fields.get("createdAtEpochMicros")).isEqualTo(expectedEpochMicros(content.getCreatedAt()));
    }

    @Test
    @DisplayName("toUpdateFields()는 thumbnailUrl이 null이어도 예외 없이 null 값을 그대로 담는다")
    void toUpdateFields_nullThumbnailUrl_mapsNullWithoutException() {
        Content content = content();
        ReflectionTestUtils.setField(content, "thumbnailUrl", null);

        Map<String, Object> fields = mapper.toUpdateFields(content);

        assertThat(fields).containsKey("thumbnailUrl");
        assertThat(fields.get("thumbnailUrl")).isNull();
    }

    @Test
    @DisplayName("toUpdateFields()의 createdAt은 인덱스 매핑 포맷(date_hour_minute_second_millis)과 같은 문자열이다")
    void toUpdateFields_createdAtMatchesConfiguredDateFormat() {
        Content content = content();

        Map<String, Object> fields = mapper.toUpdateFields(content);

        LocalDateTime expected = LocalDateTime.ofInstant(content.getCreatedAt(), ZoneOffset.UTC);
        String expectedText = java.time.format.DateTimeFormatter
                .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS").format(expected);
        assertThat(fields.get("createdAt")).isEqualTo(expectedText);
    }

    private long expectedEpochMicros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000;
    }

    private Content content() {
        Content content = Content.builder()
                .type(ContentType.MOVIE)
                .title("제목")
                .description("설명")
                .thumbnailUrl("https://example.com/thumb.jpg")
                .build();
        content.addTag("Action");
        content.addTag("SF");
        ReflectionTestUtils.setField(content, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(content, "createdAt", Instant.now());
        ReflectionTestUtils.setField(content, "averageRating", new BigDecimal("4.5"));
        ReflectionTestUtils.setField(content, "reviewCount", 3L);
        return content;
    }
}
