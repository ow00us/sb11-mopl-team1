package com.mopl.global.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class EventTopicResolverTest {

    @Test
    @DisplayName("DM 이벤트 타입을 DM 이벤트 토픽으로 변환")
    void resolve_directMessageCreated_returnsDirectMessageTopic() {
        // given
        String type = "direct-message.created";

        // when
        String result = EventTopicResolver.resolve(type, 1);

        // then
        assertThat(result)
            .isEqualTo(MoplTopics.DIRECT_MESSAGE_EVENTS);
    }

    @Test
    @DisplayName("카탈로그에 없는 버전은 토픽으로 변환하지 않음")
    void resolve_unsupportedVersion_fails() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> EventTopicResolver.resolve("direct-message.created", 2)
        ).isInstanceOf(EventContractViolationException.class);
    }
}
