package com.mopl.directmessage.websocket;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class DirectMessageSubscriptionRegistry {

    private final Map<
        UUID,
        Map<UUID, Set<Subscription>>
        > subscriptions = new ConcurrentHashMap<>();

    public void activate(
        UUID userId,
        UUID conversationId,
        String sessionId,
        String subscriptionId
    ) {
        subscriptions.compute(
            userId,
            (id, conversations) -> {
                Map<UUID, Set<Subscription>> current =
                    conversations == null
                        ? new ConcurrentHashMap<>()
                        : conversations;

                current.computeIfAbsent(
                    conversationId,
                    key ->
                        ConcurrentHashMap.newKeySet()
                ).add(
                    new Subscription(
                        sessionId,
                        subscriptionId
                    )
                );

                return current;
            }
        );
    }

    public void deactivate(
        UUID userId,
        UUID conversationId,
        String sessionId,
        String subscriptionId
    ) {
        subscriptions.computeIfPresent(
            userId,
            (id, conversations) -> {
                Set<Subscription> activeSubscriptions =
                    conversations.get(conversationId);

                if (activeSubscriptions != null) {
                    activeSubscriptions.remove(
                        new Subscription(
                            sessionId,
                            subscriptionId
                        )
                    );

                    if (activeSubscriptions.isEmpty()) {
                        conversations.remove(
                            conversationId,
                            activeSubscriptions
                        );
                    }
                }

                return conversations.isEmpty()
                    ? null
                    : conversations;
            }
        );
    }

    public void deactivateSession(
        UUID userId,
        String sessionId
    ) {
        subscriptions.computeIfPresent(
            userId,
            (id, conversations) -> {
                conversations.forEach(
                    (conversationId,
                     activeSubscriptions) -> {

                        activeSubscriptions.removeIf(
                            subscription ->
                                subscription
                                    .sessionId()
                                    .equals(sessionId)
                        );

                        if (
                            activeSubscriptions.isEmpty()
                        ) {
                            conversations.remove(
                                conversationId,
                                activeSubscriptions
                            );
                        }
                    }
                );

                return conversations.isEmpty()
                    ? null
                    : conversations;
            }
        );
    }

    public boolean isActive(
        UUID userId,
        UUID conversationId
    ) {
        Map<UUID, Set<Subscription>> conversations =
            subscriptions.get(userId);

        if (conversations == null) {
            return false;
        }

        Set<Subscription> activeSubscriptions =
            conversations.get(conversationId);

        return activeSubscriptions != null
            && !activeSubscriptions.isEmpty();
    }

    private record Subscription(
        String sessionId,
        String subscriptionId
    ) {
    }
}
