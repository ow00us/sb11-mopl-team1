package com.mopl.directmessage.dto;

import java.time.Instant;
import java.util.UUID;

public record DirectMessageReadEvent(
    UUID conversationId,
    UUID readId,
    UUID lastReadMessageId,
    Instant readAt
) {
}
