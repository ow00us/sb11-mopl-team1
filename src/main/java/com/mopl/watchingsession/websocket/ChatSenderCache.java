package com.mopl.watchingsession.websocket;

import com.mopl.global.common.UserSummary;
import java.util.Map;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

/**
 * STOMP 세션(WebSocket 연결) attribute에 인증된 사용자의 UserSummary를 캐싱한다.
 * CONNECT 시점에 1회 저장해두면, 이후 같은 연결의 모든 SEND(예: 채팅 전송)에서
 * User 테이블을 재조회하지 않고 세션 메모리에서 즉시 꺼내 쓸 수 있다.
 *
 * WatchSubscriptionAttributes와 달리 값을 소비(remove)하지 않고 연결이 유지되는 동안 계속 조회(get)한다.
 */
public final class ChatSenderCache {

    private static final String ATTRIBUTE_KEY = "watchingSession.chatSender";

    private ChatSenderCache(){}

    public static void put(SimpMessageHeaderAccessor accessor, UserSummary sender) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return; // CONNECT 자체가 비정상인 극단적 케이스
        }
        sessionAttributes.put(ATTRIBUTE_KEY, sender);
    }

    public static UserSummary get(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }
        Object value = sessionAttributes.get(ATTRIBUTE_KEY);
        return value instanceof UserSummary summary ? summary : null;
    }

}
