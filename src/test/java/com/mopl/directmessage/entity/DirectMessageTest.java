package com.mopl.directmessage.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DirectMessageTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    private static final UUID SENDER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    @Test
    @DisplayName("읽지 않은 DM을 읽음 처리하면 readAt을 기록")
    void markAsRead_unread_recordsReadAt() {
        // given
        DirectMessage message = createMessage();

        Instant readAt =
            Instant.parse("2026-07-31T01:00:00Z");

        // when
        message.markAsRead(readAt);

        // then
        assertThat(message.getReadAt())
            .isEqualTo(readAt);
    }

    @Test
    @DisplayName("이미 읽은 DM을 다시 읽음 처리하면 최초 readAt을 유지")
    void markAsRead_alreadyRead_preservesFirstReadAt() {
        // given
        DirectMessage message = createMessage();

        Instant firstReadAt =
            Instant.parse("2026-07-31T01:00:00Z");

        Instant secondReadAt =
            Instant.parse("2026-07-31T02:00:00Z");

        message.markAsRead(firstReadAt);

        // when
        message.markAsRead(secondReadAt);

        // then
        assertThat(message.getReadAt())
            .isEqualTo(firstReadAt);
    }

    private DirectMessage createMessage() {
        return DirectMessage.create(
            CONVERSATION_ID,
            SENDER_ID,
            "안녕하세요"
        );
    }
}
