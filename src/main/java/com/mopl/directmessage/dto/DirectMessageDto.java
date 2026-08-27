package com.mopl.directmessage.dto;

import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.global.common.UserSummary;

import java.time.Instant;
import java.util.UUID;

public record DirectMessageDto(
    UUID id,
    UUID conversationId,
    Instant createdAt,
    long messageSequence,
    UserSummary sender,
    UserSummary receiver,
    String content,
    Instant readAt
) {

    public DirectMessageDto(
        UUID id,
        UUID conversationId,
        Instant createdAt,
        long messageSequence,
        UserSummary sender,
        UserSummary receiver,
        String content
    ) {
        this(
            id,
            conversationId,
            createdAt,
            messageSequence,
            sender,
            receiver,
            content,
            null
        );
    }

    public static DirectMessageDto from(
        DirectMessage directMessage,
        UserSummary sender,
        UserSummary receiver
    ) {
        return new DirectMessageDto(
            directMessage.getId(),
            directMessage.getConversationId(),
            directMessage.getCreatedAt(),
            directMessage.getMessageSequence(),
            sender,
            receiver,
            directMessage.getContent(),
            directMessage.getReadAt()
        );
    }
}
