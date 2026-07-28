package com.mopl.global.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * 원본 OpenAPI의 커서 페이지네이션 응답 계약입니다.
 * cursor와 idAfter는 각각 주 정렬 키와 ID 타이브레이커 값을 전달합니다.
 */
@Schema(description = "커서 방식 목록 조회 응답")
public record CursorResponse<T>(
        @Schema(description = "조회 결과") List<T> data,
        @Schema(description = "다음 요청에 사용할 주 정렬 커서", nullable = true) String nextCursor,
        @Schema(description = "다음 요청에 사용할 보조 UUID 커서", nullable = true) UUID nextIdAfter,
        @Schema(description = "다음 페이지 존재 여부") boolean hasNext,
        @Schema(description = "조회 조건에 맞는 전체 개수") long totalCount,
        @Schema(description = "적용한 정렬 기준") String sortBy,
        @Schema(
                description = "적용한 정렬 방향",
                allowableValues = {"ASCENDING", "DESCENDING"}
        ) String sortDirection
) {
    public static <T> CursorResponse<T> of(
            List<T> data,
            String nextCursor,
            UUID nextIdAfter,
            boolean hasNext,
            long totalCount,
            String sortBy,
            String sortDirection
    ) {
        return new CursorResponse<>(
                data,
                nextCursor,
                nextIdAfter,
                hasNext,
                totalCount,
                sortBy,
                sortDirection
        );
    }
}
