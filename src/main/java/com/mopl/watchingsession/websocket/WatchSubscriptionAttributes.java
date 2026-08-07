package com.mopl.watchingsession.websocket;

import com.mopl.watchingsession.dto.SubscriptionConsumeResult;
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
 * 소유권(어느 구독이 현재 유효한지) 판정은 이 클래스가 하지 않는다.
 * WatchingSessionService.activeSessions가 (sessionId, subscriptionId) 쌍으로 그 판정을 전담한다.
 * 이 클래스는 두 가지만 담당한다:
 *   1. subscriptionId -> contentId 매핑 (UNSUBSCRIBE 시점에 destination을 복원하기 위함)
 *   2. 이 연결에서 마지막으로 activate()된 subscriptionId 기록
 *      (DISCONNECT 시점에는 subscriptionId가 프레임에 없으므로, "이 연결이 마지막으로
 *      소유하고 있던 구독이 무엇인지"를 알아내기 위해 WatchingSessionDisconnectListener가 사용)
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

        synchronized (sessionAttributes) {
            Map<String, UUID> map = subscriptionMapLocked(sessionAttributes);
            map.put(subscriptionId, contentId);
            // 활성 ID 전환은 여기서 하지 않는다. 매핑 등록(put)과 "이 구독이 실제로 유효한
            // 시청 세션으로 이어졌다"는 서로 다른 시점의 사실이다. put 직후 start()가 실패하면
            // 아직 유효하지 않은 구독이 활성으로 전환돼, 이전에 진짜 활성이던 구독을 밀어내고
            // 그 자신도 즉시 롤백되어 "활성 구독 없음" 상태가 되는 문제가 생긴다.
            // 활성 전환은 start()가 성공한 뒤 activate()로 별도 호출한다.
            }
        return true;
    }

    /**
     * SUBSCRIBE 처리(start())가 성공한 뒤에만 호출한다.
     * 이 subscriptionId를 이 연결의 활성 구독으로 전환한다.
     * put() 시점이 아니라 여기서 전환해야, start() 실패 시 이전 활성 구독이 잘못 밀려나지 않는다.
     */
    static void activate(StompHeaderAccessor accessor) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) {
            return;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return;
        }

        synchronized (sessionAttributes) {
            sessionAttributes.put(ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY, subscriptionId);
        }
    }

    /**
     * 이 연결에서 현재 활성인 subscriptionId를 반환한다.
     * DISCONNECT처럼 특정 subscriptionId를 알지 못한 채(연결 자체가 끊긴 상황)
     * "그래서 이 연결이 마지막으로 소유하고 있던 구독이 뭐였는지"를 조회해야 하는 경우에 사용한다.
     * 활성 구독이 없거나 세션 attribute 자체가 없으면 null을 반환한다.
     */
    static String currentActiveSubscriptionId(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }

        synchronized (sessionAttributes) {
            Object value = sessionAttributes.get(ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY);
            return value instanceof String subscriptionId ? subscriptionId : null;
        }
    }

    /**
     * UNSUBSCRIBE 처리 전용 API.
     * 매핑에서 subscriptionId에 대응하는 contentId를 꺼내면서 동시에 제거한다.
     */
    static SubscriptionConsumeResult consume(SimpMessageHeaderAccessor accessor) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) {
            return SubscriptionConsumeResult.NO_MAPPING;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return SubscriptionConsumeResult.NO_MAPPING;
        }

        synchronized (sessionAttributes) {
            Map<String, UUID> map = subscriptionMapLocked(sessionAttributes);
            UUID contentId = map.remove(subscriptionId);
            if (contentId == null) {
                return SubscriptionConsumeResult.NO_MAPPING;
            }

            return SubscriptionConsumeResult.mapped(contentId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, UUID> subscriptionMapLocked(Map<String, Object> sessionAttributes) {
        // 호출자가 이미 sessionAttributes에 대한 synchronized 블록 안에 있어야 한다.
        return (Map<String, UUID>) sessionAttributes
            .computeIfAbsent(SUBSCRIPTION_MAP_ATTRIBUTE_KEY, key -> new ConcurrentHashMap<String, UUID>());
    }
}

