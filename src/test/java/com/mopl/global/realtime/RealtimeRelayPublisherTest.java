package com.mopl.global.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class RealtimeRelayPublisherTest {

    private static final String EVENT_TYPE = "content.chat";
    private static final String DESTINATION = "/sub/contents/content-1/chat";

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ObjectMapper objectMapper = spy(new ObjectMapper());
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RealtimeRelayMetrics metrics = new RealtimeRelayMetrics(meterRegistry);
    private final RealtimeRelayPublisher publisher = new RealtimeRelayPublisher(
        redis, objectMapper, new RealtimeInstanceId("instance-1"), metrics);

    @AfterEach
    void closeRegistry() {
        meterRegistry.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    @DisplayName("eventType이 null 또는 공백이면 발행 전에 거부한다")
    void rejectsMissingEventTypeBeforePublishing(String eventType) {
        assertThatThrownBy(() -> publisher.publish(eventType, DESTINATION, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventType");

        verifyNoInteractions(redis, objectMapper);
        assertPublishCounts(0, 0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    @DisplayName("destination이 null 또는 공백이면 발행 전에 거부한다")
    void rejectsMissingDestinationBeforePublishing(String destination) {
        assertThatThrownBy(() -> publisher.publish(EVENT_TYPE, destination, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("destination");

        verifyNoInteractions(redis, objectMapper);
        assertPublishCounts(0, 0);
    }

    @Test
    @DisplayName("정상 입력은 식별자와 원본 payload를 포함한 JSON을 발행한다")
    void publishesCompleteEnvelope() throws Exception {
        Map<String, Object> payload = Map.of("body", "안녕하세요");

        assertThat(publisher.publish(EVENT_TYPE, DESTINATION, payload)).isTrue();

        ArgumentCaptor<String> serialized = ArgumentCaptor.forClass(String.class);
        verify(redis).convertAndSend(eq(RealtimeChannels.MESSAGES), serialized.capture());
        RealtimeMessage sent = objectMapper.readValue(serialized.getValue(), RealtimeMessage.class);
        assertThat(sent.messageId()).isNotNull();
        assertThat(sent.originInstanceId()).isEqualTo("instance-1");
        assertThat(sent.eventType()).isEqualTo(EVENT_TYPE);
        assertThat(sent.destination()).isEqualTo(DESTINATION);
        assertThat(sent.payload()).isEqualTo(objectMapper.valueToTree(payload));
        assertPublishCounts(1, 0);
    }

    @Test
    @DisplayName("payload 변환 실패는 예외 전파 없이 false와 실패 지표로 남긴다")
    void isolatesPayloadConversionFailure() {
        doThrow(new IllegalArgumentException("payload conversion failed"))
            .when(objectMapper).valueToTree(any());

        assertThat(publisher.publish(EVENT_TYPE, DESTINATION, Map.of())).isFalse();

        verifyNoInteractions(redis);
        assertPublishCounts(0, 1);
    }

    @Test
    @DisplayName("JSON 직렬화 실패는 Redis를 호출하지 않고 false를 반환한다")
    void isolatesJsonSerializationFailure() throws Exception {
        doThrow(new JsonProcessingException("serialization failed") { })
            .when(objectMapper).writeValueAsString(any(RealtimeMessage.class));

        assertThat(publisher.publish(EVENT_TYPE, DESTINATION, Map.of())).isFalse();

        verifyNoInteractions(redis);
        assertPublishCounts(0, 1);
    }

    @Test
    @DisplayName("Redis 발행 실패를 격리한 뒤 다음 정상 발행을 처리한다")
    void acceptsNextPublishAfterRedisFailure() {
        when(redis.convertAndSend(eq(RealtimeChannels.MESSAGES), anyString()))
            .thenThrow(new RedisConnectionFailureException("Redis disconnected"))
            .thenReturn(1L);

        assertThat(publisher.publish(EVENT_TYPE, DESTINATION, Map.of())).isFalse();
        assertThat(publisher.publish(EVENT_TYPE, DESTINATION, Map.of())).isTrue();

        assertPublishCounts(1, 1);
    }

    private void assertPublishCounts(double succeeded, double failed) {
        assertThat(meterRegistry.get("mopl.realtime.relay.published.messages")
            .tag("outcome", "succeeded").counter().count()).isEqualTo(succeeded);
        assertThat(meterRegistry.get("mopl.realtime.relay.published.messages")
            .tag("outcome", "failed").counter().count()).isEqualTo(failed);
    }
}
