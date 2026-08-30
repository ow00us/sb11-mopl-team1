package com.mopl.directmessage.websocket;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.security.Principal;
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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@ExtendWith(MockitoExtension.class)
class DirectMessageDisconnectListenerTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    @Mock
    private DirectMessageSubscriptionRegistry
        subscriptionRegistry;

    @InjectMocks
    private DirectMessageDisconnectListener listener;

    @Test
    @DisplayName("WebSocket 연결이 종료되면 해당 세션의 DM 구독 상태를 제거")
    void onDisconnect_success() {
        // given
        SessionDisconnectEvent event =
            createEvent(
                "session-1",
                principalOf(USER_ID)
            );

        // when
        listener.onDisconnect(event);

        // then
        verify(subscriptionRegistry).deactivateSession(
            USER_ID,
            "session-1"
        );
    }

    @Test
    @DisplayName("인증 사용자가 없으면 DM 세션 종료 상태를 처리하지 않음")
    void onDisconnect_principalMissing_ignores() {
        // given
        SessionDisconnectEvent event =
            createEvent(
                "session-1",
                null
            );

        // when
        listener.onDisconnect(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("인증 사용자 ID가 UUID 형식이 아니면 DM 세션 종료 상태를 처리하지 않음")
    void onDisconnect_invalidPrincipal_ignores() {
        // given
        SessionDisconnectEvent event =
            createEvent(
                "session-1",
                () -> "invalid-user-id"
            );

        // when
        listener.onDisconnect(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    @Test
    @DisplayName("WebSocket 세션 ID가 없으면 DM 세션 종료 상태를 처리하지 않음")
    void onDisconnect_sessionIdMissing_ignores() {
        // given
        SessionDisconnectEvent event =
            createEvent(
                null,
                principalOf(USER_ID)
            );

        // when
        listener.onDisconnect(event);

        // then
        verifyNoInteractions(subscriptionRegistry);
    }

    private SessionDisconnectEvent createEvent(
        String sessionId,
        Principal principal
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.create(
                StompCommand.DISCONNECT
            );

        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);

        if (principal != null) {
            accessor.setUser(principal);
        }

        Message<byte[]> message =
            MessageBuilder.createMessage(
                new byte[0],
                accessor.getMessageHeaders()
            );

        return new SessionDisconnectEvent(
            this,
            message,
            sessionId == null
                ? "event-session"
                : sessionId,
            CloseStatus.NORMAL,
            principal
        );
    }

    private Principal principalOf(
        UUID userId
    ) {
        return () -> userId.toString();
    }
}
