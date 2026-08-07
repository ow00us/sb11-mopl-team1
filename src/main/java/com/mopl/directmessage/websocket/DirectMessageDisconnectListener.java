package com.mopl.directmessage.websocket;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectMessageDisconnectListener {

    private final DirectMessageSubscriptionRegistry
        subscriptionRegistry;

    @EventListener
    public void onDisconnect(
        SessionDisconnectEvent event
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.wrap(
                event.getMessage()
            );

        UUID userId =
            extractUserId(
                accessor.getUser()
            );

        String sessionId =
            accessor.getSessionId();

        if (
            userId == null
                || sessionId == null
        ) {
            log.warn(
                "DM 연결 종료 상태를 처리할 수 없습니다."
            );
            return;
        }

        subscriptionRegistry.deactivateSession(
            userId,
            sessionId
        );
    }

    private UUID extractUserId(
        Principal principal
    ) {
        if (
            principal == null
                || principal.getName() == null
        ) {
            return null;
        }

        try {
            return UUID.fromString(
                principal.getName()
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
