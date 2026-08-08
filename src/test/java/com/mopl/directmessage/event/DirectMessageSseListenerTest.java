package com.mopl.directmessage.event;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.dto.DirectMessageCreatedEvent;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.websocket.DirectMessageSubscriptionRegistry;
import com.mopl.global.common.UserSummary;
import com.mopl.sse.service.SseEmitterManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

class DirectMessageSseListenerTest {

    private static final UUID MESSAGE_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID RECEIVER_ID =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    private DirectMessageSubscriptionRegistry
        subscriptionRegistry;

    private SseEmitterManager sseEmitterManager;

    private DirectMessageSseListener listener;

    @BeforeEach
    void setUp() {
        subscriptionRegistry =
            mock(
                DirectMessageSubscriptionRegistry.class
            );

        sseEmitterManager =
            mock(SseEmitterManager.class);

        listener =
            new DirectMessageSseListener(
                subscriptionRegistry,
                sseEmitterManager
            );
    }

    @Test
    @DisplayName("수신자가 해당 대화를 보고 있지 않으면 DM SSE를 전송한다")
    void sendDirectMessage_inactiveConversation_sendsSse() {
        DirectMessageDto message =
            createMessage();

        when(
            subscriptionRegistry.isActive(
                RECEIVER_ID,
                CONVERSATION_ID
            )
        ).thenReturn(false);

        listener.sendDirectMessage(
            new DirectMessageCreatedEvent(message)
        );

        verify(sseEmitterManager).send(
            RECEIVER_ID,
            MESSAGE_ID,
            "direct-messages",
            message
        );
    }

    @Test
    @DisplayName("수신자가 해당 대화를 보고 있으면 DM SSE를 전송하지 않는다")
    void sendDirectMessage_activeConversation_doesNotSendSse() {
        DirectMessageDto message =
            createMessage();

        when(
            subscriptionRegistry.isActive(
                RECEIVER_ID,
                CONVERSATION_ID
            )
        ).thenReturn(true);

        listener.sendDirectMessage(
            new DirectMessageCreatedEvent(message)
        );

        verify(
            sseEmitterManager,
            never()
        ).send(
            RECEIVER_ID,
            MESSAGE_ID,
            "direct-messages",
            message
        );
    }

    private DirectMessageDto createMessage() {
        DirectMessageDto message =
            mock(DirectMessageDto.class, Answers.RETURNS_DEEP_STUBS);

        when(message.id())
            .thenReturn(MESSAGE_ID);

        when(message.conversationId())
            .thenReturn(CONVERSATION_ID);

        when(message.receiver().userId())
            .thenReturn(RECEIVER_ID);

        return message;
    }
}
