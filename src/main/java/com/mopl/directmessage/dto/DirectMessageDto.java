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
    Instant readAt,
    UUID clientMessageId
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
            null,
            null
        );
    }

    public DirectMessageDto(
        UUID id,
        UUID conversationId,
        Instant createdAt,
        long messageSequence,
        UserSummary sender,
        UserSummary receiver,
        String content,
        Instant readAt
    ) {
        this(
            id,
            conversationId,
            createdAt,
            messageSequence,
            sender,
            receiver,
            content,
            readAt,
            null
        );
    }

    public static DirectMessageDto from(
        DirectMessage directMessage,
        UserSummary sender,
        UserSummary receiver
    ) {
        return from(
            directMessage,
            sender,
            receiver,
            null
        );
    }

    public static DirectMessageDto from(
        DirectMessage directMessage,
        UserSummary sender,
        UserSummary receiver,
        UUID clientMessageId
    ) {
        return new DirectMessageDto(
            directMessage.getId(),
            directMessage.getConversationId(),
            directMessage.getCreatedAt(),
            directMessage.getMessageSequence(),
            sender,
            receiver,
            directMessage.getContent(),
            directMessage.getReadAt(),
            clientMessageId
        );
    }
}
