package com.mopl.notification.dto;

import java.util.List;
import java.util.UUID;

public record NotificationCursorResponse(
    List<NotificationDto> data,
    String nextCursor,
    UUID nextIdAfter,
    boolean hasNext,
    long totalCount,
    long unreadCount,
    String sortBy,
    String sortDirection
) {

    public static NotificationCursorResponse of(
        List<NotificationDto> data,
        String nextCursor,
        UUID nextIdAfter,
        boolean hasNext,
        long totalCount,
        long unreadCount,
        String sortBy,
        String sortDirection
    ) {
        return new NotificationCursorResponse(
            data,
            nextCursor,
            nextIdAfter,
            hasNext,
            totalCount,
            unreadCount,
            sortBy,
            sortDirection
        );
    }
}
