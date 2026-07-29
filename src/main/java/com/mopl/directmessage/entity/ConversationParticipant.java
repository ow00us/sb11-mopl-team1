package com.mopl.directmessage.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "conversation_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationParticipant extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    private ConversationParticipant(UUID conversationId, UUID userId) {
        this.conversationId = conversationId;
        this.userId = userId;
    }

    public static ConversationParticipant create(
        UUID conversationId,
        UUID userId
    ) {
        return new ConversationParticipant(conversationId, userId);
    }
}
