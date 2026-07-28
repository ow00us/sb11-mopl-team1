package com.mopl.global.common;

import java.util.List;
import java.util.UUID;

/**
 * 원본 OpenAPI의 커서 페이지네이션 응답 계약입니다.
 * cursor와 idAfter는 각각 주 정렬 키와 ID 타이브레이커 값을 전달합니다.
 */
public record CursorResponse<T>(
        List<T> data,
        String nextCursor,
        UUID nextIdAfter,
        boolean hasNext,
        long totalCount,
        String sortBy,
        String sortDirection
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
