package com.mopl.watchingsession.websocket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

/**
 * STOMP 세션(WebSocket 연결) attribute에 subscriptionId -> contentId 매핑을 저장/조회한다.
 * SUBSCRIBE 프레임에는 destination이 있지만 UNSUBSCRIBE 프레임에는 subscriptionId만 있어서,
 * 입장(SUBSCRIBE) 시점에 저장해둔 값을 퇴장(UNSUBSCRIBE) 시점에 꺼내 쓰기 위한 용도.
 *
 * 세션 attribute 자체가 연결(WebSocket 세션) 단위로 격리되므로 사용자별로 별도 동기화가 필요 없고,
 * 같은 연결에서 여러 콘텐츠를 순차 구독/해제하는 경우까지 다루기 위해 subscriptionId를 키로 둔다.
 */
@Slf4j
final class WatchSubscriptionAttributes {

    public static final String ATTRIBUTE_KEY = "watchingSession.subscriptions";

    private WatchSubscriptionAttributes() {}

    static void put(SimpMessageHeaderAccessor accessor, UUID contentId) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) return;
        subscriptionMap(accessor).put(subscriptionId, contentId);
    }

    static UUID remove(SimpMessageHeaderAccessor accessor) {
        String subscriptionId = accessor.getSubscriptionId();
        if (subscriptionId == null) return null;
        return subscriptionMap(accessor).remove(subscriptionId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, UUID> subscriptionMap(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            // 세션 속성이 없다는 것은 STOMP 연결을 제대로 거치지 않은 비정상 상태
            // 다만 예외를 던지면 SessionSubscribeEvent 리스너 예외가 그대로 StompSubProtocolHandler까지 전파되어
            // 클라이언트에 STOMP ERROR 프레임이 감 -> 연결 강제 종료됨.
            // throw 대신 에러 레벨 로그로 실패를 드러내고 빈 맵으로 계속 진행
            log.error("WebSocket session 속성이 비어있어 시청 세션 구독 매핑을 처리할 수 없습니다.");
            return new ConcurrentHashMap<>();
        }
        return (Map<String, UUID>) sessionAttributes
            .computeIfAbsent(ATTRIBUTE_KEY, key -> new ConcurrentHashMap<String, UUID>());
    }
}
