package com.mopl.review.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewTest {

    @Test
    @DisplayName("유효한 rating으로 생성하면 필드가 그대로 설정된다")
    void builder_setsFields() {
        UUID authorId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        Review review = Review.builder()
                .authorId(authorId)
                .contentId(contentId)
                .text("정말 재밌게 봤어요")
                .rating(new BigDecimal("4.5"))
                .build();

        assertThat(review.getAuthorId()).isEqualTo(authorId);
        assertThat(review.getContentId()).isEqualTo(contentId);
        assertThat(review.getText()).isEqualTo("정말 재밌게 봤어요");
        assertThat(review.getRating()).isEqualByComparingTo("4.5");
    }

    @Test
    @DisplayName("rating이 null이면 생성 시점에 BusinessException이 발생한다")
    void builder_fail_nullRating() {
        assertThatThrownBy(() -> Review.builder()
                .authorId(UUID.randomUUID())
                .contentId(UUID.randomUUID())
                .text("text")
                .rating(null)
                .build())
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @ParameterizedTest
    @DisplayName("rating이 범위를 벗어나거나 0.5 단위가 아니면 생성 시점에 BusinessException이 발생한다")
    @ValueSource(strings = {"-0.1", "5.1", "1.2", "3.3"})
    void builder_fail_invalidRating(String invalidRating) {
        assertThatThrownBy(() -> Review.builder()
                .authorId(UUID.randomUUID())
                .contentId(UUID.randomUUID())
                .text("text")
                .rating(new BigDecimal(invalidRating))
                .build())
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @ParameterizedTest
    @DisplayName("rating이 0.0 또는 5.0이면 경계값으로 정상 생성된다")
    @ValueSource(strings = {"0.0", "5.0"})
    void builder_success_boundaryRating(String boundaryRating) {
        Review review = Review.builder()
                .authorId(UUID.randomUUID())
                .contentId(UUID.randomUUID())
                .text("text")
                .rating(new BigDecimal(boundaryRating))
                .build();

        assertThat(review.getRating()).isEqualByComparingTo(boundaryRating);
    }
}