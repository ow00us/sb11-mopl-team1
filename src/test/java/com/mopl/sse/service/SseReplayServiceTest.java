package com.mopl.sse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.repository.DirectMessageReplayProjection;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.notification.dto.NotificationDto;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseReplayServiceTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID CURSOR_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID MESSAGE_ID =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    private static final UUID NOTIFICATION_ID =
        UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

    private static final UUID SENDER_ID =
        UUID.fromString(
            "66666666-6666-6666-6666-666666666666"
        );

    private SseEmitterManager sseEmitterManager;
    private SseEventPositionResolver eventPositionResolver;
    private NotificationRepository notificationRepository;
    private DirectMessageRepository directMessageRepository;
    private SseReplayService replayService;

    @BeforeEach
    void setUp() {
        sseEmitterManager =
            mock(SseEmitterManager.class);

        eventPositionResolver =
            mock(SseEventPositionResolver.class);

        notificationRepository =
            mock(NotificationRepository.class);

        directMessageRepository =
            mock(DirectMessageRepository.class);

        replayService =
            new SseReplayService(
                sseEmitterManager,
                eventPositionResolver,
                notificationRepository,
                directMessageRepository
            );
    }

    @Test
    @DisplayName("Last-Event-ID가 없으면 새 SSE 연결만 생성")
    void subscribe_withoutLastEventId_createsConnectionOnly() {
        // given
        SseEmitter emitter =
            mock(SseEmitter.class);

        when(
            sseEmitterManager.subscribe(USER_ID)
        ).thenReturn(emitter);

        when(
            eventPositionResolver.resolve(
                USER_ID,
                null
            )
        ).thenReturn(Optional.empty());

        // when
        SseEmitter result =
            replayService.subscribe(
                USER_ID,
                null
            );

        // then
        assertThat(result).isSameAs(emitter);

        verifyNoInteractions(
            notificationRepository,
            directMessageRepository
        );
    }

    @Test
    @DisplayName("누락된 DM과 알림을 생성 시간 순서로 새 연결에 Replay")
    void subscribe_withLastEventId_replaysInCreatedOrder() {
        // given
        Instant cursor =
            Instant.parse("2026-08-24T01:00:00Z");

        Instant messageCreatedAt =
            Instant.parse("2026-08-24T02:00:00Z");

        Instant notificationCreatedAt =
            Instant.parse("2026-08-24T03:00:00Z");

        SseEmitter emitter =
            mock(SseEmitter.class);

        Notification notification =
            mock(Notification.class);

        DirectMessageReplayProjection message =
            mock(DirectMessageReplayProjection.class);

        when(
            sseEmitterManager.subscribe(USER_ID)
        ).thenReturn(emitter);

        when(
            eventPositionResolver.resolve(
                USER_ID,
                CURSOR_ID.toString()
            )
        ).thenReturn(
            Optional.of(
                new SseEventPosition(
                    CURSOR_ID,
                    cursor
                )
            )
        );

        stubNotification(
            notification,
            notificationCreatedAt
        );

        stubMessage(
            message,
            messageCreatedAt
        );

        when(
            notificationRepository.findAllForReplay(
                eq(USER_ID),
                eq(cursor),
                eq(CURSOR_ID),
                any(Pageable.class)
            )
        ).thenReturn(List.of(notification));

        when(
            directMessageRepository
                .findAllReceivedForReplay(
                    eq(USER_ID),
                    eq(cursor),
                    eq(CURSOR_ID),
                    any(Pageable.class)
                )
        ).thenReturn(List.of(message));

        // when
        SseEmitter result =
            replayService.subscribe(
                USER_ID,
                CURSOR_ID.toString()
            );

        // then
        assertThat(result).isSameAs(emitter);

        InOrder inOrder =
            inOrder(sseEmitterManager);

        inOrder.verify(sseEmitterManager)
            .subscribe(USER_ID);

        inOrder.verify(sseEmitterManager)
            .send(
                eq(USER_ID),
                eq(emitter),
                eq(MESSAGE_ID),
                eq("direct-messages"),
                any(DirectMessageDto.class)
            );

        inOrder.verify(sseEmitterManager)
            .send(
                eq(USER_ID),
                eq(emitter),
                eq(NOTIFICATION_ID),
                eq("notifications"),
                any(NotificationDto.class)
            );
    }

    private void stubNotification(
        Notification notification,
        Instant createdAt
    ) {
        when(notification.getId())
            .thenReturn(NOTIFICATION_ID);

        when(notification.getCreatedAt())
            .thenReturn(createdAt);

        when(notification.getReceiverId())
            .thenReturn(USER_ID);

        when(notification.getTitle())
            .thenReturn("알림 제목");

        when(notification.getContent())
            .thenReturn("알림 내용");

        when(notification.getLevel())
            .thenReturn(NotificationLevel.INFO);
    }

    private void stubMessage(
        DirectMessageReplayProjection message,
        Instant createdAt
    ) {
        when(message.getId())
            .thenReturn(MESSAGE_ID);

        when(message.getConversationId())
            .thenReturn(CONVERSATION_ID);

        when(message.getCreatedAt())
            .thenReturn(createdAt);

        when(message.getMessageSequence())
            .thenReturn(1L);

        when(message.getSenderId())
            .thenReturn(SENDER_ID);

        when(message.getSenderName())
            .thenReturn("발신자");

        when(message.getReceiverId())
            .thenReturn(USER_ID);

        when(message.getReceiverName())
            .thenReturn("수신자");

        when(message.getContent())
            .thenReturn("누락된 메시지");
    }
}
