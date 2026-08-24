package com.mopl.global.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 인스턴스 간 실시간 중계를 실제 Redis 로 검증합니다.
 *
 * <p>컨텍스트가 곧 하나의 인스턴스입니다. 두 인스턴스 사이의 전달을 확인하려면 상대가
 * 필요하므로, 두 번째 인스턴스는 다른 식별자로 구독자와 구독 컨테이너를 직접 만들어 씁니다.
 * 애플리케이션을 두 번 띄우는 방법보다 무엇을 검증하는지가 드러납니다.
 *
 * <p>중계에 필요한 빈만 올립니다. 전체 컨텍스트를 띄우면 데이터베이스와 Kafka 까지 필요한데,
 * 검증 대상과 관련이 없습니다.
 */
@SpringBootTest(classes = {
    RealtimeRelayConfig.class,
    RealtimeRelayPublisher.class,
    RealtimeInstanceId.class,
    RealtimeRelayMetrics.class,
    RealtimeRelayStateMetrics.class,
    RealtimeRelaySubscriptionStarter.class,
    RealtimeRelayIntegrationTest.LocalHandlerConfig.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class
})
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = "mopl.realtime.relay.enabled=true")
class RealtimeRelayIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    RealtimeRelayPublisher publisher;

    @Autowired
    RealtimeInstanceId localInstanceId;

    @Autowired
    RecordingHandler localHandler;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedisConnectionFactory redisConnectionFactory;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RealtimeRelayListenerContainer localContainer;

    @Autowired
    RealtimeRelaySubscriptionStarter subscriptionStarter;

    @Autowired
    MeterRegistry meterRegistry;

    /** 두 번째 인스턴스입니다. 식별자가 달라야 자기 메시지 판정이 갈립니다. */
    RealtimeInstanceId remoteInstanceId;
    RecordingHandler remoteHandler;
    FailingHandler remoteFailingHandler;
    RedisMessageListenerContainer remoteContainer;

    /** 두 번째 인스턴스의 지표입니다. 수신 쪽 판정을 발행 쪽 지표와 섞지 않습니다. */
    SimpleMeterRegistry remoteRegistry;

    /** 받은 메시지를 모아 두는 목적지 handler 입니다. 도메인 broadcaster 자리에 해당합니다. */
    static class RecordingHandler implements RealtimeMessageHandler {

        static final String SUPPORTED = "notification.created";

        private final List<RealtimeMessage> received = new CopyOnWriteArrayList<>();

        @Override
        public boolean supports(String eventType) {
            return SUPPORTED.equals(eventType);
        }

        @Override
        public void handle(RealtimeMessage message) {
            received.add(message);
        }

        List<RealtimeMessage> received() {
            return received;
        }

        void reset() {
            received.clear();
        }
    }

    /** 전달에 실패하는 handler 입니다. 한 목적지의 실패가 다른 목적지를 막는지 확인합니다. */
    static class FailingHandler implements RealtimeMessageHandler {

        private int calls;

        @Override
        public boolean supports(String eventType) {
            return true;
        }

        @Override
        public void handle(RealtimeMessage message) {
            calls++;
            throw new IllegalStateException("연결이 이미 끊긴 목적지");
        }

        int calls() {
            return calls;
        }
    }

    @TestConfiguration
    static class LocalHandlerConfig {

        @Bean
        RecordingHandler localHandler() {
            return new RecordingHandler();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @BeforeEach
    void startRemoteInstance() {
        localHandler.reset();

        remoteInstanceId = new RealtimeInstanceId("remote-instance");
        remoteHandler = new RecordingHandler();
        remoteFailingHandler = new FailingHandler();

        remoteRegistry = new SimpleMeterRegistry();
        RealtimeRelaySubscriber remoteSubscriber = new RealtimeRelaySubscriber(
            objectMapper, remoteInstanceId, List.of(remoteFailingHandler, remoteHandler),
            new RealtimeRelayMetrics(remoteRegistry));

        remoteContainer = new RedisMessageListenerContainer();
        remoteContainer.setConnectionFactory(redisConnectionFactory);
        remoteContainer.addMessageListener(
            remoteSubscriber, new ChannelTopic(RealtimeChannels.MESSAGES));
        remoteContainer.afterPropertiesSet();
        remoteContainer.start();

        // 구독이 실제로 붙기 전에 발행하면 메시지가 사라집니다. Pub/Sub 은 보관하지 않습니다.
        await().atMost(TIMEOUT).until(remoteContainer::isRunning);
    }

    @AfterEach
    void stopRemoteInstance() throws Exception {
        if (remoteContainer != null) {
            remoteContainer.destroy();
        }
    }

    private double remoteDiscarded(String reason) {
        return remoteRegistry.get("mopl.realtime.relay.discarded.messages")
            .tag("reason", reason).counter().count();
    }

    private double localDiscarded(String reason) {
        return meterRegistry.get("mopl.realtime.relay.discarded.messages")
            .tag("reason", reason).counter().count();
    }

    private double localPublished(String outcome) {
        return meterRegistry.get("mopl.realtime.relay.published.messages")
            .tag("outcome", outcome).counter().count();
    }

    private double subscribedGauge() {
        return meterRegistry.get("mopl.realtime.relay.subscribed").gauge().value();
    }

    private void sendRaw(String json) {
        stringRedisTemplate.convertAndSend(RealtimeChannels.MESSAGES, json);
    }

    private String rawMessage(UUID messageId, String originInstanceId, String eventType) {
        try {
            return objectMapper.writeValueAsString(new RealtimeMessage(
                messageId, originInstanceId, eventType, "/topic/user/1",
                objectMapper.valueToTree(Map.of("body", "안녕하세요"))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("한 인스턴스가 발행한 메시지를 다른 인스턴스가 받는다")
    void publish_isReceivedByOtherInstance() {
        // 카운터는 컨텍스트를 공유하는 테스트 사이에 누적되므로 증가분으로 판정합니다.
        double publishedBefore = localPublished("succeeded");

        publisher.publish(RecordingHandler.SUPPORTED, "/topic/user/1", Map.of("body", "안녕하세요"));

        await().atMost(TIMEOUT).until(() -> !remoteHandler.received().isEmpty());

        RealtimeMessage received = remoteHandler.received().get(0);
        assertThat(received.originInstanceId()).isEqualTo(localInstanceId.value());
        assertThat(received.eventType()).isEqualTo(RecordingHandler.SUPPORTED);
        assertThat(received.destination()).isEqualTo("/topic/user/1");
        assertThat(received.payload().get("body").asText()).isEqualTo("안녕하세요");
        assertThat(received.messageId()).isNotNull();

        assertThat(localPublished("succeeded")).isEqualTo(publishedBefore + 1);
        assertThat(remoteRegistry.get("mopl.realtime.relay.delivered.messages")
            .counter().count()).isEqualTo(1);
    }

    /**
     * 발행한 인스턴스는 자기 메시지를 되받아도 전달하지 않습니다.
     *
     * <p>발행 시점에 이미 자기 연결로 보낸 뒤이므로, 되받아 다시 전달하면 같은 사용자에게 두
     * 번 갑니다.
     */
    @Test
    @DisplayName("자기가 발행한 메시지는 자기 인스턴스에서 전달하지 않는다")
    void publish_doesNotLoopBackToOrigin() {
        double selfDiscardedBefore = localDiscarded("self");

        publisher.publish(RecordingHandler.SUPPORTED, "/topic/user/1", Map.of("body", "안녕하세요"));

        await().atMost(TIMEOUT).until(() -> !remoteHandler.received().isEmpty());

        assertThat(localHandler.received()).isEmpty();
        // 자기 메시지 폐기는 설계대로 동작하고 있다는 뜻입니다. 계약이 깨진 폐기와 구분됩니다.
        await().atMost(TIMEOUT)
            .until(() -> localDiscarded("self") == selfDiscardedBefore + 1);
    }

    @Test
    @DisplayName("같은 messageId가 다시 와도 한 번만 전달한다")
    void duplicateMessageId_isDeliveredOnce() {
        UUID messageId = UUID.randomUUID();
        String json = rawMessage(messageId, "another-instance", RecordingHandler.SUPPORTED);

        sendRaw(json);
        sendRaw(json);

        await().atMost(TIMEOUT).until(() -> !remoteHandler.received().isEmpty());

        // 두 번째가 늦게 도착할 수 있으므로 잠시 더 지켜본 뒤 판정합니다.
        await().during(Duration.ofSeconds(2)).atMost(TIMEOUT)
            .until(() -> remoteHandler.received().size() == 1);

        assertThat(remoteDiscarded("duplicate")).isEqualTo(1);
    }

    /**
     * 한 메시지의 실패가 구독 자체를 멈추면 그 인스턴스는 이후 모든 실시간 전달을 잃습니다.
     */
    @Test
    @DisplayName("형식이 깨진 메시지를 버리고 이어지는 메시지는 전달한다")
    void malformedMessage_isIsolated() {
        sendRaw("not-a-json-message");
        sendRaw(rawMessage(UUID.randomUUID(), "another-instance", RecordingHandler.SUPPORTED));

        await().atMost(TIMEOUT).until(() -> remoteHandler.received().size() == 1);

        assertThat(remoteDiscarded("malformed")).isEqualTo(1);
    }

    @Test
    @DisplayName("필수 값이 빠진 메시지는 전달하지 않는다")
    void messageWithoutRequiredFields_isDropped() {
        sendRaw("{\"messageId\":null,\"originInstanceId\":\"another-instance\","
            + "\"eventType\":\"notification.created\",\"destination\":\"/topic/user/1\","
            + "\"payload\":{}}");
        sendRaw(rawMessage(UUID.randomUUID(), "another-instance", RecordingHandler.SUPPORTED));

        await().atMost(TIMEOUT).until(() -> remoteHandler.received().size() == 1);
    }

    @Test
    @DisplayName("handler 하나가 실패해도 다른 handler는 전달받는다")
    void failingHandler_doesNotBlockOthers() {
        sendRaw(rawMessage(UUID.randomUUID(), "another-instance", RecordingHandler.SUPPORTED));

        await().atMost(TIMEOUT).until(() -> !remoteHandler.received().isEmpty());
        assertThat(remoteFailingHandler.calls()).isEqualTo(1);

        assertThat(remoteRegistry.get("mopl.realtime.relay.handler.failures")
            .tag("handler", "FailingHandler").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("지원하지 않는 eventType은 handler로 넘기지 않는다")
    void unsupportedEventType_isNotDispatched() {
        sendRaw(rawMessage(UUID.randomUUID(), "another-instance", "chat.message.created"));
        sendRaw(rawMessage(UUID.randomUUID(), "another-instance", RecordingHandler.SUPPORTED));

        await().atMost(TIMEOUT).until(() -> remoteHandler.received().size() == 1);
        assertThat(remoteHandler.received().get(0).eventType())
            .isEqualTo(RecordingHandler.SUPPORTED);
    }

    /**
     * 발행 실패가 호출부로 전파되면 REST 요청의 트랜잭션이 롤백됩니다.
     *
     * <p>실시간 전달은 부가 경로입니다. Redis 연결이 끊겼다는 이유로 이미 성공한 도메인 변경을
     * 되돌리면 안 됩니다.
     */
    @Test
    @DisplayName("Redis 연결이 끊겨도 발행이 예외를 던지지 않는다")
    void publish_doesNotThrowWhenRedisIsUnreachable() {
        LettuceConnectionFactory broken = new LettuceConnectionFactory(
            // 아무도 듣고 있지 않은 포트입니다.
            new RedisStandaloneConfiguration("127.0.0.1", 1),
            LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(1))
                .build());
        broken.afterPropertiesSet();
        broken.start();

        try {
            StringRedisTemplate brokenTemplate = new StringRedisTemplate(broken);
            SimpleMeterRegistry brokenRegistry = new SimpleMeterRegistry();
            RealtimeRelayPublisher brokenPublisher = new RealtimeRelayPublisher(
                brokenTemplate, objectMapper, localInstanceId,
                new RealtimeRelayMetrics(brokenRegistry));

            assertThat(brokenPublisher.publish(
                RecordingHandler.SUPPORTED, "/topic/user/1", Map.of("body", "안녕하세요"))).isFalse();

            // 발행 실패는 호출부로 전파되지 않으므로 이 카운터가 유일한 흔적입니다.
            assertThat(brokenRegistry.get("mopl.realtime.relay.published.messages")
                .tag("outcome", "failed").counter().count()).isEqualTo(1);
        } finally {
            broken.destroy();
        }
    }

    /**
     * 기동 시점에 Redis 가 준비되지 않아 구독을 붙이지 못한 뒤의 복구입니다.
     *
     * <p>한 번 실패하고 끝나면 그 인스턴스는 재배포 전까지 다른 인스턴스의 메시지를 영영
     * 받지 못합니다.
     */
    @Test
    @DisplayName("구독이 끊긴 인스턴스는 다시 시작해 전달을 이어간다")
    void subscriptionStarter_resubscribes() {
        localContainer.stop();
        assertThat(localContainer.isListening()).isFalse();
        assertThat(subscribedGauge()).isZero();

        subscriptionStarter.ensureSubscribed();
        await().atMost(TIMEOUT).until(localContainer::isListening);

        // 구독이 끊긴 구간과 복구가 지표에 드러나야 그동안 놓친 전달 범위를 정할 수 있습니다.
        assertThat(subscribedGauge()).isEqualTo(1);

        sendRaw(rawMessage(UUID.randomUUID(), "another-instance", RecordingHandler.SUPPORTED));

        await().atMost(TIMEOUT).until(() -> !localHandler.received().isEmpty());
    }
}
