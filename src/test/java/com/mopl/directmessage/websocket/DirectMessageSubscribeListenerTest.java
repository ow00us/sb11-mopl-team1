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
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@ExtendWith(MockitoExtension.class)
class DirectMessageSubscribeListenerTest {

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
    private DirectMessageSubscribeListener listener;

    @Test
    @DisplayName("DM 대화 경로를 구독하면 활성 구독 상태를 등록")
    void onSubscribe_success() {
        // given
        SessionSubscribeEvent event =
            createEvent(
                directMessageDestination(),
                "session-1",
                "subscription-1",
                principalOf(USER_ID),
                new HashMap<>()
            );

        // when
        listener.onSubscribe(event);

        // then
        verify(subscriptionRegistry).activate(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );
    }

    @Test
    @DisplayName("DM 경로가 아닌 구독 이벤트는 무시")
    void onSubscribe_notDirectMessageDestination_ignores() {
        // given
        SessionSubscribeEvent event =
            createEvent(
                "/sub/contents/" + CONVERSATION_ID,
                "session-1",
                "subscription-1",
                principalOf(USER_ID),
                new HashMap<>()
            );

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("구독 목적지가 없으면 DM 구독 상태를 등록하지 않음")
    void onSubscribe_destinationMissing_ignores() {
        // given
        SessionSubscribeEvent event =
            createEvent(
                null,
                "session-1",
                "subscription-1",
                principalOf(USER_ID),
                new HashMap<>()
            );

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("인증 사용자가 없으면 DM 구독 상태를 등록하지 않음")
    void onSubscribe_principalMissing_ignores() {
        // given
        SessionSubscribeEvent event =
            createEvent(
                directMessageDestination(),
                "session-1",
                "subscription-1",
                null,
                new HashMap<>()
            );

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("인증 사용자 ID가 UUID 형식이 아니면 DM 구독 상태를 등록하지 않음")
    void onSubscribe_invalidPrincipal_ignores() {
        // given
        SessionSubscribeEvent event =
            createEvent(
                directMessageDestination(),
                "session-1",
                "subscription-1",
                () -> "invalid-user-id",
                new HashMap<>()
            );

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("WebSocket 세션 ID가 없으면 DM 구독 상태를 등록하지 않음")
    void onSubscribe_sessionIdMissing_ignores() {
        // given
        SessionSubscribeEvent event =
            createEvent(
                directMessageDestination(),
                null,
                "subscription-1",
                principalOf(USER_ID),
                new HashMap<>()
            );

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("subscriptionId가 없으면 DM 구독 상태를 등록하지 않음")
    void onSubscribe_subscriptionIdMissing_ignores() {
        // given
        SessionSubscribeEvent event =
            createEvent(
                directMessageDestination(),
                "session-1",
                null,
                principalOf(USER_ID),
                new HashMap<>()
            );

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("세션 속성이 없으면 DM 구독 상태를 등록하지 않음")
    void onSubscribe_sessionAttributesMissing_ignores() {
        // given
        SessionSubscribeEvent event =
            createEvent(
                directMessageDestination(),
                "session-1",
                "subscription-1",
                principalOf(USER_ID),
                null
            );

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    private SessionSubscribeEvent createEvent(
        String destination,
        String sessionId,
        String subscriptionId,
        Principal principal,
        Map<String, Object> sessionAttributes
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.create(
                StompCommand.SUBSCRIBE
            );

        accessor.setDestination(destination);
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

        return new SessionSubscribeEvent(
            this,
            message
        );
    }

    private Principal principalOf(
        UUID userId
    ) {
        return () -> userId.toString();
    }

    private String directMessageDestination() {
        return "/sub/conversations/"
            + CONVERSATION_ID
            + "/direct-messages";
    }
}
