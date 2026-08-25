package com.mopl.sse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SseEventPositionResolverTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID EVENT_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    private static final UUID SENDER_ID =
        UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

    private NotificationRepository notificationRepository;
    private DirectMessageRepository directMessageRepository;
    private ConversationParticipantRepository
        participantRepository;
    private SseEventPositionResolver resolver;

    @BeforeEach
    void setUp() {
        notificationRepository =
            mock(NotificationRepository.class);

        directMessageRepository =
            mock(DirectMessageRepository.class);

        participantRepository =
            mock(
                ConversationParticipantRepository.class
            );

        resolver =
            new SseEventPositionResolver(
                notificationRepository,
                directMessageRepository,
                participantRepository
            );
    }

    @Test
    @DisplayName("수신한 알림 ID로 마지막 SSE 이벤트 위치를 조회")
    void resolve_notification_returnsPosition() {
        // given
        Instant createdAt =
            Instant.parse("2026-08-24T01:00:00Z");

        Notification notification =
            mock(Notification.class);

        when(notification.getId())
            .thenReturn(EVENT_ID);

        when(notification.getCreatedAt())
            .thenReturn(createdAt);

        when(
            notificationRepository.findByIdAndReceiverId(
                EVENT_ID,
                USER_ID
            )
        ).thenReturn(Optional.of(notification));

        // when
        Optional<SseEventPosition> result =
            resolver.resolve(
                USER_ID,
                EVENT_ID.toString()
            );

        // then
        assertThat(result)
            .contains(
                new SseEventPosition(
                    EVENT_ID,
                    createdAt
                )
            );
    }

    @Test
    @DisplayName("수신한 DM ID로 마지막 SSE 이벤트 위치를 조회")
    void resolve_receivedDirectMessage_returnsPosition() {
        // given
        Instant createdAt =
            Instant.parse("2026-08-24T01:00:00Z");

        DirectMessage message =
            mock(DirectMessage.class);

        when(message.getId())
            .thenReturn(EVENT_ID);

        when(message.getCreatedAt())
            .thenReturn(createdAt);

        when(message.getConversationId())
            .thenReturn(CONVERSATION_ID);

        when(message.getSenderId())
            .thenReturn(SENDER_ID);

        when(
            notificationRepository.findByIdAndReceiverId(
                EVENT_ID,
                USER_ID
            )
        ).thenReturn(Optional.empty());

        when(
            directMessageRepository.findById(EVENT_ID)
        ).thenReturn(Optional.of(message));

        when(
            participantRepository
                .existsByConversationIdAndUserId(
                    CONVERSATION_ID,
                    USER_ID
                )
        ).thenReturn(true);

        // when
        Optional<SseEventPosition> result =
            resolver.resolve(
                USER_ID,
                EVENT_ID.toString()
            );

        // then
        assertThat(result)
            .contains(
                new SseEventPosition(
                    EVENT_ID,
                    createdAt
                )
            );
    }

    @Test
    @DisplayName("UUID 형식이 아닌 Last-Event-ID는 Replay 위치를 반환하지 않음")
    void resolve_invalidEventId_returnsEmpty() {
        // when
        Optional<SseEventPosition> result =
            resolver.resolve(
                USER_ID,
                "invalid-event-id"
            );

        // then
        assertThat(result).isEmpty();

        verifyNoInteractions(
            notificationRepository,
            directMessageRepository,
            participantRepository
        );
    }

    @Test
    @DisplayName("발신한 DM ID는 Replay 위치로 사용하지 않음")
    void resolve_sentDirectMessage_returnsEmpty() {
        // given
        DirectMessage message =
            mock(DirectMessage.class);

        when(message.getSenderId())
            .thenReturn(USER_ID);

        when(
            notificationRepository.findByIdAndReceiverId(
                EVENT_ID,
                USER_ID
            )
        ).thenReturn(Optional.empty());

        when(
            directMessageRepository.findById(EVENT_ID)
        ).thenReturn(Optional.of(message));

        // when
        Optional<SseEventPosition> result =
            resolver.resolve(
                USER_ID,
                EVENT_ID.toString()
            );

        // then
        assertThat(result).isEmpty();
    }
}
