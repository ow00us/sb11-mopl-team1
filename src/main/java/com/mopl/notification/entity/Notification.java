package com.mopl.notification.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    @Column(name = "receiver_id", nullable = false)
    private UUID receiverId;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationLevel level;

    @Column(name = "read_at")
    private Instant readAt;

    private Notification(
        UUID receiverId,
        UUID sourceEventId,
        String title,
        String content,
        NotificationLevel level
    ) {
        this.receiverId = receiverId;
        this.sourceEventId = sourceEventId;
        this.title = title;
        this.content = content;
        this.level = level;
    }

    public static Notification create(
        UUID receiverId,
        UUID sourceEventId,
        String title,
        String content,
        NotificationLevel level
    ) {
        return new Notification(
            receiverId,
            sourceEventId,
            title,
            content,
            level
        );
    }

    public void markAsRead(Instant readAt) {
        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }



}
