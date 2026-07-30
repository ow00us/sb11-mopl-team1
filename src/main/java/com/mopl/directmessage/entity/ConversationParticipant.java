package com.mopl.directmessage.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "conversation_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationParticipant extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_slot", nullable = false, length = 10)
    private ParticipantSlot participantSlot;

    private ConversationParticipant(
        UUID conversationId,
        UUID userId,
        ParticipantSlot participantSlot
    ) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.participantSlot = participantSlot;
    }

    public static ConversationParticipant create(
        UUID conversationId,
        UUID userId,
        ParticipantSlot participantSlot
    ) {
        return new ConversationParticipant(
            conversationId,
            userId,
            participantSlot
        );
    }
}
