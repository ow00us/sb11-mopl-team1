package com.mopl.content.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.math.BigDecimal;
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
    @DisplayName("update 호출 시 태그 중 하나라도 유효하지 않으면 기존 태그가 유지된 채로 예외가 발생한다")
    void update_withInvalidTag_keepsExistingTagsAndThrows() {
        Content content = movie();
        content.addTag("action");

        assertThatThrownBy(() -> content.update(null, null, Set.of("sf", "   ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(content.getTags()).containsExactly("action");
    }

    @Test
    @DisplayName("정규화 후 태그 길이가 100자를 초과하면 예외가 발생한다")
    void normalize_fail_tagTooLong() {
        String tooLong = "a".repeat(101);

        assertThatThrownBy(() -> Content.normalize(tooLong))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("정규화 후 태그 길이가 정확히 100자면 통과한다")
    void normalize_success_tagExactly100Chars() {
        String exactly100 = "a".repeat(100);

        String result = Content.normalize(exactly100);

        assertThat(result).hasSize(100);
    }

    @Test
    @DisplayName("updateReviewAggregate 호출 시 averageRating/reviewCount가 반영된다")
    void updateReviewAggregate_updatesFields() {
        Content content = movie();

        content.updateReviewAggregate(new BigDecimal("4.3"), 7L);

        assertThat(content.getAverageRating()).isEqualByComparingTo("4.3");
        assertThat(content.getReviewCount()).isEqualTo(7L);
    }

    @Test
    @DisplayName("updateReviewAggregate 호출 시 averageRating이 null이면 0.0으로 처리된다")
    void updateReviewAggregate_nullAverageRating_defaultsToZero() {
        Content content = movie();

        content.updateReviewAggregate(null, 0L);

        assertThat(content.getAverageRating()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(content.getReviewCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("updateReviewAggregate 호출 시 소수 둘째 자리 이하 평균은 첫째 자리로 반올림된다")
    void updateReviewAggregate_roundsToOneDecimalPlace() {
        Content content = movie();

        content.updateReviewAggregate(new BigDecimal("4.166666666666667"), 3L);

        assertThat(content.getAverageRating()).isEqualByComparingTo("4.2");
    }

    @Test
    @DisplayName("updateReviewAggregate 호출 시 반올림 경계값(x.x5)은 HALF_UP으로 올림된다")
    void updateReviewAggregate_halfUpBoundary_roundsUp() {
        Content content = movie();

        content.updateReviewAggregate(new BigDecimal("4.25"), 4L);

        assertThat(content.getAverageRating()).isEqualByComparingTo("4.3");
    }

    @Test
    @DisplayName("updateThumbnail 호출 시 thumbnailUrl이 변경된다")
    void updateThumbnail_changesThumbnailUrl() {
        Content content = movie();

        content.updateThumbnail("https://example.com/new.png");

        assertThat(content.getThumbnailUrl()).isEqualTo("https://example.com/new.png");
    }
}