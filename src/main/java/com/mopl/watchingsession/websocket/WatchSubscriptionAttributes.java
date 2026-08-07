package com.mopl.watchingsession.websocket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

/**
 * STOMP 세션(WebSocket 연결) attribute에 subscriptionId -> contentId 매핑을 저장/조회한다.
 * SUBSCRIBE 프레임에는 destination이 있지만 UNSUBSCRIBE 프레임에는 subscriptionId만 있어서,
 * 입장(SUBSCRIBE) 시점에 저장해둔 값을 퇴장(UNSUBSCRIBE) 시점에 꺼내 쓰기 위한 용도.
 *
 * 세션 attribute 자체가 연결(WebSocket 세션) 단위로 격리되므로 사용자별로 별도 동기화가 필요 없고,
 * 같은 연결에서 여러 콘텐츠를 순차 구독/해제하는 경우까지 다루기 위해 subscriptionId를 키로 둔다.
 */
final class WatchSubscriptionAttributes {

    public static final String SUBSCRIPTION_MAP_ATTRIBUTE_KEY = "watchingSession.subscriptionMap";
    public static final String ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY = "watchingSession.activeSubscriptionId";

    private WatchSubscriptionAttributes() {}

    public static boolean put(StompHeaderAccessor accessor, UUID contentId) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) {
            return false; // subscriptionId가 없으면 매핑 불가
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return false;
        }

        Map<String, UUID> map = getOrCreateSubscriptionMap(accessor);
        if (map == null) {
            return false;
        }

        map.put(subscriptionId, contentId);
        sessionAttributes.put(ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY, subscriptionId);
        return true;
    }

    public static UUID remove(SimpMessageHeaderAccessor accessor) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) return null;

        Map<String, UUID> map = getOrCreateSubscriptionMap(accessor);
        if (map == null) {
            return null;
        }

        return map.remove(subscriptionId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, UUID> getOrCreateSubscriptionMap(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }

        // Spring의 Session Attributes 맵에 대해 동기화 처리 후 Map 가져오기
        synchronized (sessionAttributes) {
            return (Map<String, UUID>) sessionAttributes
                .computeIfAbsent(SUBSCRIPTION_MAP_ATTRIBUTE_KEY, key -> new ConcurrentHashMap<String, UUID>());
        }
    }

    static boolean isActive(SimpMessageHeaderAccessor accessor) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) {
            return false;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return false;
        }

        return subscriptionId.equals(sessionAttributes.get(ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY));
        }
    }


