package com.mopl.directmessage.websocket;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mopl.directmessage.presence.DirectMessagePresenceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectMessageSubscriptionRegistry {

    private final Map<
        UUID,
        Map<UUID, Set<Subscription>>
        > subscriptions = new ConcurrentHashMap<>();

    private final DirectMessagePresenceStore presenceStore;

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

        try {
            presenceStore.register(
                userId,
                conversationId,
                sessionId,
                subscriptionId
            );
        } catch (RuntimeException exception) {
            log.warn(
                "Redis DM 활성 상태 등록에 실패했습니다. "
                + "로컬 상태를 유지합니다: "
                + "userId={}, conversationId={}, "
                + "sessionId={}",
                userId,
                conversationId,
                sessionId,
                exception
            );
        }
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

        try {
            presenceStore.unregister(
                userId,
                conversationId,
                sessionId,
                subscriptionId
            );
        } catch (RuntimeException exception) {
            log.warn(
                "Redis DM 활성 상태 해제에 실패했습니다: "
                    + "userId={}, conversationId={}, "
                    + "sessionId={}",
                userId,
                conversationId,
                sessionId,
                exception
            );
        }
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

        try {
            presenceStore.unregisterSession(
                userId,
                sessionId
            );
        } catch (RuntimeException exception) {
            log.warn(
                "Redis DM 세션 활성 상태 제거에 실패했습니다: "
                    + "userId={}, sessionId={}",
                userId,
                sessionId,
                exception
            );
        }
    }

    public boolean isActive(
        UUID userId,
        UUID conversationId
    ) {
        if (
            isActiveLocally(
                userId,
                conversationId
            )
        ) {
            return true;
        }

        try {
            return presenceStore.isActive(
                userId,
                conversationId
            );
        } catch (RuntimeException exception) {
            log.warn(
                "Redis DM 활성 상태 조회에 실패했습니다. "
                    + "비활성 상태로 처리합니다: "
                    + "userId={}, conversationId={}",
                userId,
                conversationId,
                exception
            );

            return false;
        }
    }

    public void renewPresence() {
        activeSessions().forEach(
            activeSession -> {
                try {
                    presenceStore.renewSession(
                        activeSession.userId(),
                        activeSession.sessionId()
                    );
                } catch (RuntimeException exception) {
                    log.warn(
                        "Redis DM 활성 상태 TTL 갱신에 "
                            + "실패했습니다: "
                            + "userId={}, sessionId={}",
                        activeSession.userId(),
                        activeSession.sessionId(),
                        exception
                    );
                }
            }
        );
    }

    private boolean isActiveLocally(
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

    private Set<ActiveSession> activeSessions() {
        Set<ActiveSession> activeSessions =
            new HashSet<>();

        subscriptions.forEach(
            (userId, conversations) ->
                conversations.values().forEach(
                    activeSubscriptions ->
                        activeSubscriptions.forEach(
                            subscription ->
                                activeSessions.add(
                                    new ActiveSession(
                                        userId,
                                        subscription.sessionId()
                                    )
                                )
                        )
                )
        );

        return activeSessions;
    }

    private record Subscription(
        String sessionId,
        String subscriptionId
    ) {
    }

    private record ActiveSession(
        UUID userId,
        String sessionId
    ) {
    }
}
