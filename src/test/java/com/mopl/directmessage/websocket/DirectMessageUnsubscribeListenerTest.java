package com.mopl.directmessage.websocket;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@ExtendWith(MockitoExtension.class)
class DirectMessageUnsubscribeListenerTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    @Mock
    private DirectMessageSubscriptionRegistry
        subscriptionRegistry;

    @InjectMocks
    private DirectMessageUnsubscribeListener listener;

    @Test
    @DisplayName("DM 구독을 해제하면 활성 구독 상태를 제거")
    void onUnsubscribe_success() {
        // given
        SessionUnsubscribeEvent event =
            createEventWithMapping(
                "session-1",
                "subscription-1",
                principalOf(USER_ID)
            );

        // when
        listener.onUnsubscribe(event);

        // then
        verify(subscriptionRegistry).deactivate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );
    }

    @Test
    @DisplayName("DM 구독 매핑이 없으면 구독 해제 상태를 처리하지 않음")
    void onUnsubscribe_mappingMissing_ignores() {
        // given
        SessionUnsubscribeEvent event =
            createEvent(
                "session-1",
                "subscription-1",
                principalOf(USER_ID),
                new HashMap<>()
            );

        // when
        listener.onUnsubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("subscriptionId가 없으면 구독 해제 상태를 처리하지 않음")
    void onUnsubscribe_subscriptionIdMissing_ignores() {
        // given
        SessionUnsubscribeEvent event =
            createEvent(
                "session-1",
                null,
                principalOf(USER_ID),
                new HashMap<>()
            );

        // when
        listener.onUnsubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("인증 사용자가 없으면 구독 해제 상태를 처리하지 않음")
    void onUnsubscribe_principalMissing_ignores() {
        // given
        SessionUnsubscribeEvent event =
            createEventWithMapping(
                "session-1",
                "subscription-1",
                null
            );

        // when
        listener.onUnsubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("인증 사용자 ID가 UUID 형식이 아니면 구독 해제 상태를 처리하지 않음")
    void onUnsubscribe_invalidPrincipal_ignores() {
        // given
        SessionUnsubscribeEvent event =
            createEventWithMapping(
                "session-1",
                "subscription-1",
                () -> "invalid-user-id"
            );

        // when
        listener.onUnsubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("WebSocket 세션 ID가 없으면 구독 해제 상태를 처리하지 않음")
    void onUnsubscribe_sessionIdMissing_ignores() {
        // given
        SessionUnsubscribeEvent event =
            createEventWithMapping(
                null,
                "subscription-1",
                principalOf(USER_ID)
            );

        // when
        listener.onUnsubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    private SessionUnsubscribeEvent createEventWithMapping(
        String sessionId,
        String subscriptionId,
        Principal principal
    ) {
        Map<String, Object> sessionAttributes =
            new HashMap<>();

        StompHeaderAccessor subscribeAccessor =
            StompHeaderAccessor.create(
                StompCommand.SUBSCRIBE
            );

        subscribeAccessor.setSubscriptionId(
            subscriptionId
        );

        subscribeAccessor.setSessionAttributes(
            sessionAttributes
        );

        DirectMessageSubscriptionAttributes.put(
            subscribeAccessor,
            CONVERSATION_ID
        );

        return createEvent(
            sessionId,
            subscriptionId,
            principal,
            sessionAttributes
        );
    }

    private SessionUnsubscribeEvent createEvent(
        String sessionId,
        String subscriptionId,
        Principal principal,
        Map<String, Object> sessionAttributes
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.create(
                StompCommand.UNSUBSCRIBE
            );

        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setLeaveMutable(true);

        if (principal != null) {
            accessor.setUser(principal);
        }

        Message<byte[]> message =
            MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
            );

        return new SessionUnsubscribeEvent(
            this,
            message
        );
    }

    private Principal principalOf(
        UUID userId
    ) {
        return () -> userId.toString();
    }
}
