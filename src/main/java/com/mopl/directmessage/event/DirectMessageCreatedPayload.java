package com.mopl.directmessage.event;

import java.util.UUID;

public record DirectMessageCreatedPayload(
    UUID directMessageId,
    UUID conversationId,
    UUID senderId,
    UUID receiverId,
    String contentPreview
) {
}
