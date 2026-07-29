package com.mopl.content.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentTest {

    private Content movie() {
        return Content.builder()
                .type(ContentType.MOVIE)
                .title("원래 제목")
                .description("원래 설명")
                .build();
    }

    @Test
    @DisplayName("update 호출 시 null이 아닌 필드만 변경된다")
    void update_onlyNonNullFieldsChanged() {
        Content content = movie();

        content.update("새 제목", null, null);

        assertThat(content.getTitle()).isEqualTo("새 제목");
        assertThat(content.getDescription()).isEqualTo("원래 설명");
    }

    @Test
    @DisplayName("update 호출 시 title/description이 모두 null이면 기존 값이 유지된다")
    void update_allNull_keepsExistingValues() {
        Content content = movie();

        content.update(null, null, null);

        assertThat(content.getTitle()).isEqualTo("원래 제목");
        assertThat(content.getDescription()).isEqualTo("원래 설명");
    }

    @Test
    @DisplayName("update 호출 시 title과 description이 모두 non-null이면 둘 다 변경된다")
    void update_bothNonNull_bothChanged() {
        Content content = movie();

        content.update("새 제목", "새 설명", null);

        assertThat(content.getTitle()).isEqualTo("새 제목");
        assertThat(content.getDescription()).isEqualTo("새 설명");
    }

    @Test
    @DisplayName("update 호출 시 tags가 null이면 기존 태그가 유지된다")
    void update_nullTags_keepsExistingTags() {
        Content content = movie();
        content.addTag("action");

        content.update(null, null, null);

        assertThat(content.getTags()).containsExactly("action");
    }

    @Test
    @DisplayName("update 호출 시 tags가 주어지면 기존 태그를 모두 교체한다")
    void update_withTags_replacesAllTags() {
        Content content = movie();
        content.addTag("action");

        content.update(null, null, Set.of("SF", " Drama "));

        assertThat(content.getTags()).containsExactlyInAnyOrder("sf", "drama");
    }

    @Test
    @DisplayName("update 호출 시 tags가 빈 Set이면 기존 태그가 모두 제거된다")
    void update_withEmptyTags_clearsAllTags() {
        Content content = movie();
        content.addTag("action");

        content.update(null, null, Set.of());

        assertThat(content.getTags()).isEmpty();
    }

    @Test
    @DisplayName("updateThumbnail 호출 시 thumbnailUrl이 변경된다")
    void updateThumbnail_changesThumbnailUrl() {
        Content content = movie();

        content.updateThumbnail("https://example.com/new.png");

        assertThat(content.getThumbnailUrl()).isEqualTo("https://example.com/new.png");
    }
}