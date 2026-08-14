package com.mopl.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationTest {

    @Test
    @DisplayName("알림 생성 시 읽지 않은 상태로 생성")
    void create_success_unread() {
        // given
        UUID receiverId = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

        // when
        Notification notification = Notification.create(
            receiverId,
            null,
            "새로운 알림",
            "알림 내용",
            NotificationLevel.INFO
        );

        // then
        assertThat(notification.getReceiverId()).isEqualTo(receiverId);
        assertThat(notification.getTitle()).isEqualTo("새로운 알림");
        assertThat(notification.getContent()).isEqualTo("알림 내용");
        assertThat(notification.getLevel()).isEqualTo(NotificationLevel.INFO);
        assertThat(notification.getReadAt()).isNull();
    }

    @Test
    @DisplayName("읽지 않은 알림을 읽음 처리하면 readAt을 기록")
    void markAsRead_unreadNotification_recordsReadAt() {
        // given
        Notification notification = createNotification();
        Instant readAt = Instant.parse("2026-07-28T00:00:00Z");

        // when
        notification.markAsRead(readAt);

        // then
        assertThat(notification.getReadAt()).isEqualTo(readAt);
    }

    @Test
    @DisplayName("이미 읽은 알림을 다시 읽음 처리하면 최초 readAt을 유지")
    void markAsRead_alreadyRead_preservesFirstReadAt() {
        // given
        Notification notification = createNotification();
        Instant firstReadAt = Instant.parse("2026-07-28T00:00:00Z");
        Instant secondReadAt = Instant.parse("2026-07-28T01:00:00Z");

        notification.markAsRead(firstReadAt);

        // when
        notification.markAsRead(secondReadAt);

        // then
        assertThat(notification.getReadAt()).isEqualTo(firstReadAt);
    }

    private Notification createNotification() {
        return Notification.create(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            null,
            "새로운 알림",
            "알림 내용",
            NotificationLevel.INFO
        );
    }

    @Test
    @DisplayName("알림 생성 시 유형과 대상 정보를 저장")
    void create_withTargetInformation_success() {
        // given
        UUID receiverId = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

        UUID sourceEventId = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

        UUID resourceId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID sourceEntityId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        // when
        Notification notification = Notification.create(
            receiverId,
            sourceEventId,
            NotificationType.DIRECT_MESSAGE,
            resourceId,
            sourceEntityId,
            "새로운 DM",
            "메시지가 도착했습니다.",
            NotificationLevel.INFO
        );

        // then
        assertThat(notification.getReceiverId())
            .isEqualTo(receiverId);

        assertThat(notification.getSourceEventId())
            .isEqualTo(sourceEventId);

        assertThat(notification.getType())
            .isEqualTo(NotificationType.DIRECT_MESSAGE);

        assertThat(notification.getResourceId())
            .isEqualTo(resourceId);

        assertThat(notification.getSourceEntityId())
            .isEqualTo(sourceEntityId);

        assertThat(notification.getReadAt())
            .isNull();
    }
}
