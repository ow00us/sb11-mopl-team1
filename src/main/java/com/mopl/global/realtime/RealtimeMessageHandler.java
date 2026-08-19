package com.mopl.global.realtime;

/**
 * 다른 인스턴스에서 온 실시간 메시지를 받는 포트입니다.
 *
 * <p>알림·DM 과 채팅·시청 세션 broadcaster 가 이 인터페이스를 구현해 연결합니다. 공통
 * 계층은 어떤 목적지가 있는지 알지 못하고, 자기 eventType 을 처리하겠다고 밝힌 구현에만
 * 넘깁니다.
 *
 * <p>구현은 자기 인스턴스에 연결된 대상에게만 전달합니다. 여기서 다시 발행하면 메시지가
 * 인스턴스 사이를 계속 돕니다.
 */
public interface RealtimeMessageHandler {

    /** 이 handler 가 처리할 eventType 인지 판단합니다. */
    boolean supports(String eventType);

    /** 메시지를 자기 인스턴스의 연결로 전달합니다. */
    void handle(RealtimeMessage message);
}
