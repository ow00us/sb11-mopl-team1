package com.mopl.notification.dto;

import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import java.time.Instant;
import java.util.UUID;

public record NotificationDto(
    UUID id,
    Instant createdAt,
    UUID receiverId,
    String title,
    String content,
    NotificationLevel level,
    NotificationType type,
    UUID resourceId,
    Instant readAt
) {

    public NotificationDto(
        UUID id,
        Instant createdAt,
        UUID receiverId,
        String title,
        String content,
        NotificationLevel level,
        NotificationType type,
        UUID resourceId
    ) {
        this(
            id,
            createdAt,
            receiverId,
            title,
            content,
            level,
            type,
            resourceId,
            null
        );
    }

    public static NotificationDto from(
        Notification notification
    ) {
        return new NotificationDto(
            notification.getId(),
            notification.getCreatedAt(),
            notification.getReceiverId(),
            notification.getTitle(),
            notification.getContent(),
            notification.getLevel(),
            notification.getType(),
            notification.getResourceId(),
            notification.getReadAt()
        );
    }
}
