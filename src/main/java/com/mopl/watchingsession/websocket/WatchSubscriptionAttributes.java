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
 * 같은 연결 안에서 구독을 갈아탄 경우(sub-1 -> sub-2) sessionId만으로는 낡은 구독의
 * UNSUBSCRIBE를 걸러낼 수 없으므로, "이 연결에서 현재 활성인 subscriptionId"를 별도로 기록한다.
 *
 * clientInboundChannel은 기본 executor(스레드풀)를 사용하므로 SUBSCRIBE와 UNSUBSCRIBE가
 * 서로 다른 스레드에서 동시에 처리될 수 있다. "활성 여부 판정 + 매핑 소비 + 활성 ID 제거"를
 * 별도 호출로 나누면 그 사이에 다른 스레드의 SUBSCRIBE(put)가 끼어들어 activeSubscriptionId를
 * 갱신할 수 있고, 그 경우 이미 낡아진 UNSUBSCRIBE가 "활성"으로 잘못 판정될 수 있다(TOCTOU).
 * 이를 막기 위해 판정·소비·제거를 하나의 synchronized 블록(consume)으로 원자화한다.
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
            // 매핑 갱신과 활성 ID 갱신을 같은 락 안에서 수행해, 두 값이 서로 다른 시점의
            // 상태를 반영하는 일이 없도록 한다.
            sessionAttributes.put(ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY, subscriptionId);
        }
        return true;
    }


    /**
     * UNSUBSCRIBE 처리 전용 API.
     * "이 subscriptionId가 현재 활성 구독인지 판정 + 매핑에서 contentId 소비(제거) + (활성 구독이었다면)
     * 활성 ID 제거"를 하나의 락 안에서 원자적으로 수행한다.
     * 판정과 소비 사이에 다른 스레드의 SUBSCRIBE(put)가 끼어드는 것을 원천 차단하기 위해,
     * 리스너는 isActive()/remove()를 따로 호출하지 않고 이 메서드에서 사용함.
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

            boolean wasActive = subscriptionId.equals(
                sessionAttributes.get(ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY));

            if (wasActive) {
                sessionAttributes.remove(ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY);
                return SubscriptionConsumeResult.active(contentId);
            }

            return SubscriptionConsumeResult.stale(contentId);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, UUID> subscriptionMapLocked(Map<String, Object> sessionAttributes) {
        // 호출자가 이미 sessionAttributes에 대한 synchronized 블록 안에 있어야 한다.
        return (Map<String, UUID>) sessionAttributes
            .computeIfAbsent(SUBSCRIPTION_MAP_ATTRIBUTE_KEY, key -> new ConcurrentHashMap<String, UUID>());
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

