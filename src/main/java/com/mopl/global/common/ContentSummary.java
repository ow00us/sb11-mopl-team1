package com.mopl.global.common;

import java.util.List;
import java.util.UUID;

/**
 * 여러 도메인에서 공통으로 사용하는 콘텐츠 요약 DTO입니다.
 * Content 도메인 연동 후 실제 데이터로 채워집니다.
 */
public record ContentSummary(
        UUID id,
        String type,
        String title,
        String description,
        String thumbnailUrl,
        List<String> tags,
        double averageRating,
        int reviewCount
) {}