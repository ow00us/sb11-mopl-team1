package com.mopl.directmessage.repository;

import java.time.Instant;
import java.util.UUID;

public interface ConversationListItemProjection {

    UUID getConversationId();
    Instant getCreatedAt();
    UUID getWithUserId();
}
