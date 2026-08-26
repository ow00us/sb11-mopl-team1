package com.mopl.directmessage.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.global.event.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DirectMessageOutboxEventFactoryTest {

    private static final UUID DIRECT_MESSAGE_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID CONVERSATION_ID = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    private static final UUID SENDER_ID = UUID.fromString(
        "33333333-3333-3333-3333-333333333333"
    );

    private static final UUID RECEIVER_ID = UUID.fromString(
        "44444444-4444-4444-4444-444444444444"
    );

    private static final Instant CREATED_AT = Instant.parse(
        "2026-08-19T01:00:00Z"
    );

    private final DirectMessageOutboxEventFactory factory =
        new DirectMessageOutboxEventFactory(
            new ObjectMapper()
        );

    @Test
    @DisplayName("저장된 DM을 Outbox 이벤트 계약으로 변환")
    void create_savedDirectMessage_returnsEventEnvelope() {
        // given
        DirectMessage directMessage = mock(DirectMessage.class);

        when(directMessage.getId()).thenReturn(DIRECT_MESSAGE_ID);

        when(directMessage.getConversationId()).thenReturn(CONVERSATION_ID);

        when(directMessage.getSenderId()).thenReturn(SENDER_ID);

        when(directMessage.getContent()).thenReturn("  안녕하세요\n\n반갑습니다   ");

        when(directMessage.getCreatedAt()).thenReturn(CREATED_AT);

        // when
        EventEnvelope result = factory.create(
            directMessage,
            RECEIVER_ID
        );

        // then
        assertThat(result.eventId())
            .isNotNull();

        assertThat(result.type())
            .isEqualTo("direct-message.created");

        assertThat(result.version())
            .isEqualTo(1);

        assertThat(result.occurredAt())
            .isEqualTo(CREATED_AT);

        assertThat(result.aggregateId())
            .isEqualTo(DIRECT_MESSAGE_ID);

        assertThat(result.payload()
            .get("directMessageId")
            .asText()
        ). isEqualTo(DIRECT_MESSAGE_ID.toString());

        assertThat(
            result.payload()
                .get("conversationId")
                .asText()
        ).isEqualTo(
            CONVERSATION_ID.toString()
        );

        assertThat(
            result.payload()
                .get("senderId")
                .asText()
        ).isEqualTo(
            SENDER_ID.toString()
        );

        assertThat(
            result.payload()
                .get("receiverId")
                .asText()
        ).isEqualTo(
            RECEIVER_ID.toString()
        );

        assertThat(
            result.payload()
                .get("contentPreview")
                .asText()
        ).isEqualTo(
            "안녕하세요 반갑습니다"
        );
    }

    @Test
    @DisplayName("DM 미리보기를 Unicode 문자 기준 100자로 제한")
    void create_longContent_truncatesPreviewByCodePoint() {
        // given
        DirectMessage directMessage =
            mock(DirectMessage.class);

        String content =
            "😀".repeat(101);

        when(directMessage.getId())
            .thenReturn(DIRECT_MESSAGE_ID);

        when(directMessage.getConversationId())
            .thenReturn(CONVERSATION_ID);

        when(directMessage.getSenderId())
            .thenReturn(SENDER_ID);

        when(directMessage.getContent())
            .thenReturn(content);

        when(directMessage.getCreatedAt())
            .thenReturn(CREATED_AT);

        // when
        EventEnvelope result =
            factory.create(
                directMessage,
                RECEIVER_ID
            );

        String contentPreview =
            result.payload()
                .get("contentPreview")
                .asText();

        // then
        assertThat(
            contentPreview.codePointCount(
                0,
                contentPreview.length()
            )
        ).isEqualTo(100);

        assertThat(contentPreview)
            .isEqualTo(
                "😀".repeat(100)
            );
    }
}
