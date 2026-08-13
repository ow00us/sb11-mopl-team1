package com.mopl.global.event;

import java.util.List;

/**
 * Kafka 토픽 이름을 한곳에서 관리합니다.
 *
 * <p>토픽은 소비 목적이 아니라 생산 bounded context 단위로 둡니다. 하나의 도메인
 * 사실이 알림 외 다른 소비자에게도 쓰일 수 있고, 생산자가 소비 목적에 종속되는 것을
 * 막기 위해서입니다. 이벤트 타입별로 토픽을 만들지 않습니다.
 */
public final class MoplTopics {

    /** 팔로우 생산 영역. follow.created */
    public static final String FOLLOW_EVENTS = "mopl.follow.events";

    /** 플레이리스트·구독 생산 영역. playlist.subscription.created */
    public static final String PLAYLIST_EVENTS = "mopl.playlist.events";

    /**
     * 프리미어 생산 영역. premiere.upcoming, premiere.started
     *
     * <p>같은 aggregate 의 lifecycle 순서가 필요하므로 두 이벤트가 같은 토픽을 씁니다.
     * 다만 프리미어 도메인은 아직 구현되어 있지 않습니다. 데이터 모델이 확정되기 전까지
     * 이 토픽에는 생산자도 소비자도 없습니다.
     */
    public static final String PREMIERE_EVENTS = "mopl.premiere.events";

    /** Dead Letter Topic 접미사. {@code <원본 토픽>.DLT} */
    public static final String DLT_SUFFIX = ".DLT";

    private MoplTopics() {
    }

    /** 선언적으로 생성·검증할 도메인 이벤트 토픽 목록입니다. */
    public static List<String> eventTopics() {
        return List.of(FOLLOW_EVENTS, PLAYLIST_EVENTS, PREMIERE_EVENTS);
    }

    /** 원본 토픽에 대응하는 DLT 이름을 만듭니다. */
    public static String deadLetterTopicOf(String topic) {
        return topic + DLT_SUFFIX;
    }
}
