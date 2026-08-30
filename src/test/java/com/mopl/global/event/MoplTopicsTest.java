package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoplTopicsTest {

    @ParameterizedTest
    @MethodSource("eventTopics")
    @DisplayName("공통 토픽과 DLT는 검증을 통과하고 원본 이름으로 왕복 변환된다")
    void knownTopics_roundTrip(String topic) {
        String dlt = MoplTopics.deadLetterTopicOf(topic);

        assertThatCode(() -> MoplTopics.requireEventTopic(topic)).doesNotThrowAnyException();
        assertThatCode(() -> MoplTopics.requireDeadLetterTopic(dlt)).doesNotThrowAnyException();
        assertThat(dlt).isEqualTo(topic + ".DLT");
        assertThat(MoplTopics.originalTopicOf(dlt)).isEqualTo(topic);
    }

    static Stream<String> eventTopics() {
        return MoplTopics.eventTopics().stream();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "mopl.follow.events", "mopl.follow.events.dlt", "unknown.DLT",
        "mopl.follow.events.DLT.DLT", ".DLT"})
    @DisplayName("null·잘못된 접미사·계약 밖 원본의 DLT는 검증과 역변환에서 거부한다")
    void unknownDlt_isRejectedBeforeConversion(String topic) {
        assertThatThrownBy(() -> MoplTopics.requireDeadLetterTopic(topic))
            .isInstanceOf(EventContractViolationException.class);
        assertThatThrownBy(() -> MoplTopics.originalTopicOf(topic))
            .isInstanceOf(EventContractViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "mopl.follow.events.DLT"})
    @DisplayName("임의 토픽이나 DLT를 생산 토픽으로 허용하지 않는다")
    void unknownEventTopic_isRejected(String topic) {
        assertThatThrownBy(() -> MoplTopics.requireEventTopic(topic))
            .isInstanceOf(EventContractViolationException.class);
    }
}
