package com.mopl.directmessage.websocket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

final class DirectMessageSubscriptionAttributes {

    private static final String ATTRIBUTE_KEY =
        "directMessage.subscriptions";

    private DirectMessageSubscriptionAttributes() {
    }

    static boolean put(
        StompHeaderAccessor accessor,
        UUID conversationId
    ) {
        String subscriptionId =
            accessor.getSubscriptionId();

        if (subscriptionId == null) {
            return false;
        }

        Map<String, UUID> subscriptions =
            getSubscriptions(accessor);

        if (subscriptions == null) {
            return false;
        }

        subscriptions.put(
            subscriptionId,
            conversationId
        );

        return true;
    }

    static UUID remove(
        SimpMessageHeaderAccessor accessor
    ) {
        String subscriptionId =
            accessor.getSubscriptionId();

        if (subscriptionId == null) {
            return null;
        }

        Map<String, UUID> subscriptions =
            getSubscriptions(accessor);

        if (subscriptions == null) {
            return null;
        }

        return subscriptions.remove(
            subscriptionId
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, UUID> getSubscriptions(
        SimpMessageHeaderAccessor accessor
    ) {
        Map<String, Object> sessionAttributes =
            accessor.getSessionAttributes();

        if (sessionAttributes == null) {
            return null;
        }

        synchronized (sessionAttributes) {
            return (Map<String, UUID>)
                sessionAttributes.computeIfAbsent(
                    ATTRIBUTE_KEY,
                    key ->
                        new ConcurrentHashMap<
                            String,
                            UUID
                            >()
                );
        }
    }
}
