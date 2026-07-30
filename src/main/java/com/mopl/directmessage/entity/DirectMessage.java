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

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "read_at")
    private Instant readAt;

    private DirectMessage(
        UUID conversationId,
        UUID senderId,
        String content
    ) {
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.content = content;
    }

    public static DirectMessage create(
        UUID conversationId,
        UUID senderId,
        String content
    ) {
        return new DirectMessage(
            conversationId,
            senderId,
            content
        );
    }

    public void markAsRead(Instant readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }
}
