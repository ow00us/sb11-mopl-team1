package com.mopl.global.event;

/**
 * 이벤트 타입을 발행할 토픽으로 옮깁니다.
 *
 * <p>토픽은 소비 목적이 아니라 생산 bounded context 단위입니다. 타입의 첫 마디가 생산
 * 영역을 가리키므로 그 값으로 토픽을 정합니다.
 *
 * <p>등록되지 않은 타입은 예외로 막습니다. 임의 토픽을 만들어 발행하면 소비자가 없는 곳으로
 * 이벤트가 흘러가고, 잘못된 타입명이 뒤늦게 드러납니다.
 */
public final class EventTopicResolver {

    private EventTopicResolver() {
    }

    public static String resolve(String type) {
        if (type == null || type.isBlank()) {
            throw new EventContractViolationException("이벤트 type 이 비어 있습니다.");
        }

        int separator = type.indexOf('.');
        if (separator <= 0) {
            throw new EventContractViolationException(
                "이벤트 type 형식이 <domain>.<event> 가 아닙니다: " + type);
        }

        return switch (type.substring(0, separator)) {
            case "follow" -> MoplTopics.FOLLOW_EVENTS;
            case "playlist" -> MoplTopics.PLAYLIST_EVENTS;
            case "premiere" -> MoplTopics.PREMIERE_EVENTS;
            default -> throw new EventContractViolationException(
                "발행 토픽이 정해지지 않은 이벤트 type 입니다: " + type);
        };
    }
}
