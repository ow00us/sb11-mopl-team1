package com.mopl.directmessage.repository;

import java.time.Instant;
import java.util.UUID;

public interface DirectMessageReplayProjection {

    UUID getId();

    UUID getConversationId();

    Instant getCreatedAt();

    long getMessageSequence();

    UUID getSenderId();

    String getSenderName();

    String getSenderProfileImageUrl();

    UUID getReceiverId();

    String getReceiverName();

    String getReceiverProfileImageUrl();

    String getContent();
}
