package com.mopl.content.dto;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record ContentDto(
        UUID id,
        ContentType type,
        String title,
        String description,
        String thumbnailUrl,
        Set<String> tags,
        BigDecimal averageRating,
        long reviewCount,
        long watcherCount
) {
    public static ContentDto from(Content content) {
        return new ContentDto(
                content.getId(), content.getType(), content.getTitle(), content.getDescription(),
                content.getThumbnailUrl(), content.getTags(), content.getAverageRating(),
                content.getReviewCount(), content.getWatcherCount());
    }
}