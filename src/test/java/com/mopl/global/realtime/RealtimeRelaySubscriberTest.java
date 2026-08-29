package com.mopl.global.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

class RealtimeRelaySubscriberTest {

    private static final String EVENT_TYPE = "content.chat";
    private static final byte[] CHANNEL = RealtimeChannels.MESSAGES.getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RealtimeMessageHandler handler = mock(RealtimeMessageHandler.class);
    private final RealtimeRelaySubscriber subscriber = new RealtimeRelaySubscriber(
        objectMapper, new RealtimeInstanceId("receiver"), List.of(handler),
        new RealtimeRelayMetrics(meterRegistry));

    @AfterEach
    void closeRegistry() {
        meterRegistry.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageId", "originInstanceId", "eventType", "destination"})
    @DisplayName("필수 필드 하나가 null이면 폐기하고 다음 정상 메시지는 전달한다")
    void discardsEachNullFieldAndAcceptsNextMessage(String field) throws Exception {
        RealtimeMessage valid = validMessage();
        ObjectNode incomplete = objectMapper.valueToTree(valid);
        incomplete.putNull(field);

        assertIncompleteThenHealthy(incomplete, valid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageId", "originInstanceId", "eventType", "destination"})
    @DisplayName("필수 JSON 속성 하나가 없으면 폐기하고 다음 정상 메시지는 전달한다")
    void discardsEachMissingFieldAndAcceptsNextMessage(String field) throws Exception {
        RealtimeMessage valid = validMessage();
        ObjectNode incomplete = objectMapper.valueToTree(valid);
        incomplete.remove(field);

        assertIncompleteThenHealthy(incomplete, valid);
    }

    private void assertIncompleteThenHealthy(ObjectNode incomplete, RealtimeMessage valid)
        throws Exception {
        subscriber.onMessage(redisMessage(incomplete), null);

        verifyNoInteractions(handler);
        assertThat(meterRegistry.get("mopl.realtime.relay.discarded.messages")
            .tag("reason", "incomplete").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("mopl.realtime.relay.delivered.messages")
            .counter().count()).isZero();

        // 같은 messageId를 복원해 보냅니다. 폐기한 메시지는 중복 캐시를 오염시키지 않습니다.
        when(handler.supports(EVENT_TYPE)).thenReturn(true);
        subscriber.onMessage(redisMessage(valid), null);

        verify(handler).handle(valid);
        assertThat(meterRegistry.get("mopl.realtime.relay.delivered.messages")
            .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.get("mopl.realtime.relay.discarded.messages")
            .tag("reason", "duplicate").counter().count()).isZero();
    }

    private RealtimeMessage validMessage() {
        return new RealtimeMessage(UUID.randomUUID(), "sender", EVENT_TYPE,
            "/sub/contents/content-1/chat", objectMapper.valueToTree(Map.of("body", "hello")));
    }

    private Message redisMessage(Object value) throws Exception {
        return new DefaultMessage(CHANNEL, objectMapper.writeValueAsBytes(value));
    }
}
