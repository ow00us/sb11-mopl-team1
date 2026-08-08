package com.mopl.review.dto;

import com.mopl.global.common.UserSummary;
import com.mopl.review.entity.Review;
import java.math.BigDecimal;
import java.util.UUID;

public record ReviewDto(
        UUID id,
        UUID contentId,
        UserSummary author,
        String text,
        BigDecimal rating
) {
    public static ReviewDto from(Review review, UserSummary author) {
        return new ReviewDto(
                review.getId(), review.getContentId(), author, review.getText(), review.getRating());
    }
}