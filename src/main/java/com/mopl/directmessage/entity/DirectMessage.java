package com.mopl.directmessage.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "direct_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DirectMessage extends BaseEntity {

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "message_sequence", nullable = false)
    private long messageSequence;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "read_at")
    private Instant readAt;

    private DirectMessage(
        UUID conversationId,
        UUID senderId,
        long messageSequence,
        String content
    ) {
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.messageSequence = messageSequence;
        this.content = content;
    }

    public static DirectMessage create(
        UUID conversationId,
        UUID senderId,
        long messageSequence,
        String content
    ) {
        return new DirectMessage(
            conversationId,
            senderId,
            messageSequence,
            content
        );
    }

}
