package com.mopl.content.dto;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.search.ContentDocument;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
                resolveDisplayTags(document),
                BigDecimal.valueOf(document.getAverageRating()).setScale(1, RoundingMode.HALF_UP),
                document.getReviewCount(),
                document.getWatcherCount());
    }

    // displayTags는 이번에 추가된 필드라, 이 변경이 배포되기 전에 색인된 기존 문서에는 값이 없어
    // null로 역직렬화된다. null이면 재색인 전까지 정규화 값(tags)으로 대신 보여준다 —
    // 문서가 다시 생성·수정되면 ContentDocumentMapper.toUpdateFields()가 displayTags를
    // 채우면서 자동으로 원본 표기로 돌아온다.
    private static Set<String> resolveDisplayTags(ContentDocument document) {
        List<String> displayTags = document.getDisplayTags();
        return Set.copyOf(displayTags != null ? displayTags : document.getTags());
    }
}