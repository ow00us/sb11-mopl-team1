package com.mopl.content.dto;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.search.ContentDocument;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
                content.getThumbnailUrl(), Set.copyOf(content.getTags()), content.getAverageRating(),
                content.getReviewCount(), content.getWatcherCount());
    }

    public static ContentDto from(Content content, long liveWatcherCount) {
        return new ContentDto(
                content.getId(), content.getType(), content.getTitle(), content.getDescription(),
                content.getThumbnailUrl(), Set.copyOf(content.getTags()), content.getAverageRating(),
                content.getReviewCount(), liveWatcherCount);
    }

    public static ContentDto from(ContentDocument document) {
        return new ContentDto(
                UUID.fromString(document.getId()),
                ContentType.valueOf(document.getType()),
                document.getTitle(),
                document.getDescription(),
                document.getThumbnailUrl(),
                Set.copyOf(document.getDisplayTags()),
                BigDecimal.valueOf(document.getAverageRating()).setScale(1, RoundingMode.HALF_UP),
                document.getReviewCount(),
                document.getWatcherCount());
    }
}