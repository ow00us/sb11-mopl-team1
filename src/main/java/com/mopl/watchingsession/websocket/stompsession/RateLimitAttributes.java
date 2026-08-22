package com.mopl.watchingsession.websocket.stompsession;

import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.ConcurrentHashMap.newKeySet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

/**
 * STOMP 세션(WebSocket 연결) attribute에 유량·구독 개수 제한 상태를 보관한다.
 * WatchSubscriptionAttributes와 마찬가지로 연결이 종료되면 attribute와 함께 상태도
 * 자동 소멸하므로 별도 정리 로직이 필요 없다.
 *
 * 이 클래스가 담당하는 것:
 *   1. 최소 간격 판정 (chat SEND, watch 재구독) - AtomicLong CAS로 원자적 갱신
 *   2. chat 구독 개수 상한 판정 - subscriptionId Set으로 관리해 UNSUBSCRIBE 중복/누락에도
 *      카운트가 어긋나지 않게 함
 *
 * 인가 판단은 하지 않는다. 세션 attribute를 얻을 수 없는 경우 호출자가 fail-open으로
 * 처리해야 하며, 이 클래스는 그 판단에 필요한 원자적 연산만 제공한다.
 */
public class RateLimitAttributes {

    private static final String CHAT_SEND_LAST_AT_KEY = "rateLimit.chatSend.lastAt";
    private static final String WATCH_SUBSCRIBE_LAST_AT_KEY = "rateLimit.watchSubscribe.lastAt";
    private static final String CHAT_SUBSCRIPTION_IDS_KEY = "rateLimit.chatSubscriptionIds";
    private static final String HEARTBEAT_SEND_LAST_AT_KEY = "rateLimit.heartbeatSend.lastAt";

    private RateLimitAttributes() {}

    /**
     * chat SEND의 최소 간격을 만족하는지 판정하고, 만족하면 마지막 통과 시각을 갱신한다.
     * sessionAttributes가 없으면 판정 불가이므로 true(통과)를 반환한다
     */
    public static boolean tryConsumeChatSend(StompHeaderAccessor accessor, long minIntervalMillis) {
        return tryConsume(accessor, CHAT_SEND_LAST_AT_KEY, minIntervalMillis);
    }

    /**
     * watch 재구독(SUBSCRIBE)의 최소 간격을 만족하는지 판정하고, 만족하면 갱신한다.
     */
    public static boolean tryConsumeWatchSubscribe(StompHeaderAccessor accessor, long minIntervalMillis) {
        return tryConsume(accessor, WATCH_SUBSCRIBE_LAST_AT_KEY, minIntervalMillis);
    }

    public static boolean tryConsumeHeartbeatSend(StompHeaderAccessor accessor, long minIntervalMillis) {
        return tryConsume(accessor, HEARTBEAT_SEND_LAST_AT_KEY, minIntervalMillis);
    }

    private static boolean tryConsume(StompHeaderAccessor accessor, String key, long minIntervalMillis) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return true;
        }

        AtomicLong lastAt;
        synchronized (sessionAttributes) {
            lastAt = (AtomicLong) sessionAttributes.computeIfAbsent(key, k -> new AtomicLong(0L));
        }

        long now = currentTimeMillis();
        long previous = lastAt.get();
        while (now - previous >= minIntervalMillis) {
            if (lastAt.compareAndSet(previous, now)) {
                return true;
            }
            previous = lastAt.get();
        }
        return false;
    }

    /**
     * chat 구독 개수 상한 내에서 subscriptionId를 등록한다.
     * 이미 상한에 도달했으면 등록하지 않고 false를 반환한다.
     * sessionAttributes나 subscriptionId가 없으면 판정 불가이므로 true(통과)를 반환한다.
     */
    public static boolean tryAcquireChatSubscription(StompHeaderAccessor accessor, int limit) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) {
            return true;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return true;
        }

        synchronized (sessionAttributes) {
            Set<String> ids = chatSubscriptionIdsLocked(sessionAttributes);
            if (ids.contains(subscriptionId)) {
                return true; // 이미 등록된 구독(재시도 등)은 개수를 다시 소비하지 않음
            }
            if (ids.size() >= limit) {
                return false;
            }
            ids.add(subscriptionId);
            return true;
        }
    }

    /**
     * chat 구독 해제(UNSUBSCRIBE) 시 subscriptionId를 등록에서 제거한다.
     * 매핑되지 않은 subscriptionId를 넘겨도 안전하게 무시된다(Set.remove는 멱등).
     */
    public static void releaseChatSubscription(StompHeaderAccessor accessor) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) {
            return;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return;
        }

        synchronized (sessionAttributes) {
            Set<String> ids = chatSubscriptionIdsLocked(sessionAttributes);
            ids.remove(subscriptionId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> chatSubscriptionIdsLocked(Map<String, Object> sessionAttributes) {
        // 호출자가 이미 sessionAttributes에 대한 synchronized 블록 안에 있어야 한다.
        return (Set<String>) sessionAttributes
            .computeIfAbsent(CHAT_SUBSCRIPTION_IDS_KEY, key -> newKeySet());
    }
}
