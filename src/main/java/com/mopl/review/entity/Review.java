package com.mopl.review.entity;

import com.mopl.global.common.BaseEntity;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
        name = "reviews",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reviews_author_content",
                columnNames = {"author_id", "content_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseEntity {

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Builder
    public Review(UUID authorId, UUID contentId, String text, BigDecimal rating) {
        validateRating(rating);
        this.authorId = authorId;
        this.contentId = contentId;
        this.text = text;
        this.rating = rating;
    }

    public void update(String text, BigDecimal rating) {
        if (text != null) {
            this.text = text;
        }
        if (rating != null) {
            validateRating(rating);
            this.rating = rating;
        }
    }

    private static void validateRating(BigDecimal rating) {
        boolean inRange = rating != null
                && rating.compareTo(BigDecimal.ZERO) >= 0
                && rating.compareTo(BigDecimal.valueOf(5.0)) <= 0;
        boolean isHalfStep = inRange
                && rating.multiply(BigDecimal.valueOf(2)).stripTrailingZeros().scale() <= 0;
        if (!isHalfStep) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "평점은 0.0~5.0 사이 0.5 단위여야 합니다.");
        }
    }
}