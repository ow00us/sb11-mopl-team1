package com.mopl.notification.kafka;

import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import java.util.UUID;

public record NotificationCreateCommand(
    UUID receiverId,
    UUID sourceEventId,
    NotificationType type,
    UUID resourceId,
    UUID sourceEntityId,
    String title,
    String content,
    NotificationLevel level
) {
}
