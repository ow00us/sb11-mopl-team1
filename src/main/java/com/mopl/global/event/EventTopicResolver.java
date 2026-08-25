package com.mopl.global.event;

/**
 * 이벤트 타입을 발행할 토픽으로 옮깁니다.
 *
 * <p>등록된 type·version의 카탈로그 값으로 토픽을 정합니다. 접두사만 보고 토픽을
 * 추론하지 않으므로 카탈로그에 없는 새 이벤트가 조용히 발행되지 않습니다.
 */
public final class EventTopicResolver {

    private EventTopicResolver() {
    }

    public static String resolve(String type, int version) {
        return KafkaEventContract.require(type, version).topic();
    }
}
