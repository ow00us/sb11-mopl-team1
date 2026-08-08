package com.mopl.directmessage.websocket;

import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectMessageSubscribeListener {

    private static final Pattern DESTINATION_PATTERN =
        Pattern.compile(
            "^/sub/conversations/"
                + "([0-9a-fA-F-]{36})"
                + "/direct-messages$"
        );

    private final DirectMessageSubscriptionRegistry
        subscriptionRegistry;

    @EventListener
    public void onSubscribe(
        SessionSubscribeEvent event
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.wrap(
                event.getMessage()
            );

        UUID conversationId =
            extractConversationId(
                accessor.getDestination()
            );

        if (conversationId == null) {
            return;
        }

        UUID userId =
            extractUserId(
                accessor.getUser()
            );

        String sessionId =
            accessor.getSessionId();

        String subscriptionId =
            accessor.getSubscriptionId();

        if (
            userId == null
                || sessionId == null
                || subscriptionId == null
        ) {
            log.warn(
                "DM 구독 상태를 등록할 수 없습니다."
            );
            return;
        }

        boolean mapped =
            DirectMessageSubscriptionAttributes.put(
                accessor,
                conversationId
            );

        if (!mapped) {
            log.warn(
                "DM subscriptionId 매핑에 실패했습니다."
            );
            return;
        }

        subscriptionRegistry.activate(
            userId,
            conversationId,
            sessionId,
            subscriptionId
        );
    }

    private UUID extractConversationId(
        String destination
    ) {
        if (destination == null) {
            return null;
        }

        Matcher matcher =
            DESTINATION_PATTERN.matcher(
                destination
            );

        if (!matcher.matches()) {
            return null;
        }

        try {
            return UUID.fromString(
                matcher.group(1)
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
