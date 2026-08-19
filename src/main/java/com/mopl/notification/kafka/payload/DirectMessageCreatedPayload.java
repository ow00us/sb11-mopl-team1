package com.mopl.notification.kafka.payload;

import java.util.UUID;

public record DirectMessageCreatedPayload(
    UUID directMessageId,
    UUID conversationId,
    UUID senderId,
    UUID receiverId,
    String contentPreview
) {
}
