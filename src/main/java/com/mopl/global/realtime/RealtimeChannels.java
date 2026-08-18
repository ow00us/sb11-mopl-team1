package com.mopl.global.realtime;

/**
 * 인스턴스 간 실시간 중계에 쓰는 Redis 채널 이름입니다.
 *
 * <p>목적지 종류별로 채널을 나누지 않고 하나로 둡니다. 채널을 나누면 구독 목록이 도메인
 * 추가마다 바뀌고, 어느 인스턴스가 무엇을 구독 중인지가 배포 시점에 따라 갈립니다. 어떤
 * 메시지인지는 envelope 의 eventType 이 알려주므로 수신 측에서 걸러냅니다.
 */
public final class RealtimeChannels {

    /** 실시간 중계 채널의 namespace 입니다. Redis 를 다른 용도와 함께 쓰므로 접두사로 구분합니다. */
    public static final String NAMESPACE = "mopl.realtime";

    /** 인스턴스 간 실시간 메시지 채널입니다. */
    public static final String MESSAGES = NAMESPACE + ".messages";

    private RealtimeChannels() {
    }
}
