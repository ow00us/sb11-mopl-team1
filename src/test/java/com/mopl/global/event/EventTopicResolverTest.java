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
        String result = EventTopicResolver.resolve(type);

        // then
        assertThat(result)
            .isEqualTo(MoplTopics.DIRECT_MESSAGE_EVENTS);
    }
}
