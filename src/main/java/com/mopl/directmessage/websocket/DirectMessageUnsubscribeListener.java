package com.mopl.directmessage.websocket;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectMessageUnsubscribeListener {

    private final DirectMessageSubscriptionRegistry
        subscriptionRegistry;

    @EventListener
    public void onUnsubscribe(
        SessionUnsubscribeEvent event
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.wrap(
                event.getMessage()
            );

        String subscriptionId =
            accessor.getSubscriptionId();

        UUID conversationId =
            DirectMessageSubscriptionAttributes.remove(
                accessor
            );

        if (
            subscriptionId == null
                || conversationId == null
        ) {
            return;
        }

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
                "DM 구독 해제 상태를 처리할 수 없습니다."
            );
            return;
        }

        subscriptionRegistry.deactivate(
            userId,
            conversationId,
            sessionId,
            subscriptionId
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
