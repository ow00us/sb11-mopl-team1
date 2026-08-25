package com.mopl.watchingsession.websocket;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.UserSummary;
import com.mopl.global.realtime.RealtimeChannels;
import com.mopl.global.realtime.RealtimeInstanceId;
import com.mopl.global.realtime.RealtimeMessage;
import com.mopl.global.realtime.RealtimeRelayConfig;
import com.mopl.global.realtime.RealtimeRelayMetrics;
import com.mopl.global.realtime.RealtimeRelayPublisher;
import com.mopl.global.realtime.RealtimeRelaySubscriber;
import com.mopl.global.realtime.RealtimeRelaySubscriptionStarter;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.dto.WatchingSessionChange;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.ContentChatService;
import com.mopl.watchingsession.websocket.broadcast.WatchingSessionBroadcaster;
import com.mopl.watchingsession.websocket.relay.contract.ContentChatRealtimeContract;
import com.mopl.watchingsession.websocket.relay.handler.ContentChatRelayHandler;
import com.mopl.watchingsession.websocket.relay.handler.WatchingSessionRelayHandler;
import com.mopl.watchingsession.websocket.relay.payload.ContentChatRelayPayload;
import com.mopl.watchingsession.websocket.relay.publisher.ContentChatRelayPublisher;
import com.mopl.watchingsession.websocket.relay.publisher.WatchingSessionRelayPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 콘텐츠 채팅·시청 세션 변경이 서로 다른 인스턴스에 붙은 구독자에게 중계로 도달하는지 검증합니다.
 *
 * 로컬 인스턴스는 Spring 컨텍스트가 맡고, 원격 인스턴스는 다른 식별자로 구독자와 handler를 수동 구성해 시뮬레이션합니다.
 */
@SpringBootTest(classes = {
    RealtimeRelayConfig.class,
    RealtimeRelayPublisher.class,
    RealtimeRelayMetrics.class,
    RealtimeInstanceId.class,
    RealtimeRelaySubscriptionStarter.class,
    ContentChatRelayPublisher.class,
    ContentChatRelayHandler.class,
    WatchingSessionRelayPublisher.class,
    WatchingSessionRelayHandler.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class,
    ContentWatchingRelayIntegrationTest.TestMetricsConfig.class
})
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = "mopl.realtime.relay.enabled=true")
class ContentWatchingRelayIntegrationTest {

    @TestConfiguration
    static class TestMetricsConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final UUID CONTENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private ContentChatRelayPublisher contentChatRelayPublisher;

    @Autowired
    private WatchingSessionRelayPublisher watchingSessionRelayPublisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    // 로컬 인스턴스가 처리 대상 handler를 갖고 있지 않으면 dispatch 자체가 일어나지 않으므로,
    // 로컬이 발행한 메시지가 로컬로 되돌아오지 않는다를 검증하려면 로컬에도 mock이 필요하다.
    @MockitoBean
    private ContentChatService localChatService;

    @MockitoBean
    private WatchingSessionBroadcaster localWatchingBroadcaster;

    private ContentChatService remoteChatService;
    private WatchingSessionBroadcaster remoteWatchingBroadcaster;
    private RedisMessageListenerContainer remoteContainer;

    @AfterEach
    void stopRemoteInstance() throws Exception {
        if (remoteContainer != null) {
            remoteContainer.destroy();
        }
    }

    @BeforeEach
    void startRemoteInstance() {
        remoteChatService = mock(ContentChatService.class);
        remoteWatchingBroadcaster = mock(WatchingSessionBroadcaster.class);

        RealtimeInstanceId remoteInstanceId = new RealtimeInstanceId();
        ContentChatRelayHandler remoteChatHandler =
            new ContentChatRelayHandler(objectMapper, remoteChatService);
        WatchingSessionRelayHandler remoteWatchingHandler =
            new WatchingSessionRelayHandler(objectMapper, remoteWatchingBroadcaster);

        RealtimeRelaySubscriber remoteSubscriber = new RealtimeRelaySubscriber(
            objectMapper, remoteInstanceId, List.of(remoteChatHandler, remoteWatchingHandler),
            mock(RealtimeRelayMetrics.class));

        remoteContainer = new RedisMessageListenerContainer();
        remoteContainer.setConnectionFactory(redisConnectionFactory);
        remoteContainer.addMessageListener(remoteSubscriber, new ChannelTopic(RealtimeChannels.MESSAGES));
        remoteContainer.afterPropertiesSet();
        remoteContainer.start();

        // 구독이 실제로 붙기 전에 발행하면 메시지가 사라진다 (Pub/Sub는 보관하지 않음)
        await().atMost(TIMEOUT).until(remoteContainer::isRunning);
    }

    @Test
    @DisplayName("로컬에서 보낸 채팅이 원격 인스턴스 구독자에게 도달하고, 로컬 자신에게는 재도달하지 않는다")
    void contentChat_reachesRemote_notLocal() {
        // given
        ContentChatDto chatDto = new ContentChatDto(
            new UserSummary(UUID.randomUUID(), "발신자", null), "안녕하세요");

        // when
        contentChatRelayPublisher.publish(CONTENT_ID, chatDto);

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
            verify(remoteChatService).broadcast(CONTENT_ID, chatDto));

        await().during(Duration.ofMillis(500)).atMost(TIMEOUT).untilAsserted(() ->
            verifyNoInteractions(localChatService));
    }

    @Test
    @DisplayName("로컬에서 보낸 JOIN이 원격 인스턴스 구독자에게 도달한다")
    void watchingSessionJoin_reachesRemote() {
        // given
        WatchingSessionChange change = WatchingSessionChange.join(dtoFixture(), 3L);

        // when
        watchingSessionRelayPublisher.publish(change);

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
            verify(remoteWatchingBroadcaster).broadcast(CONTENT_ID, change));
    }

    @Test
    @DisplayName("로컬에서 보낸 LEAVE가 원격 인스턴스 구독자에게 도달한다")
    void watchingSessionLeave_reachesRemote() {
        // given
        WatchingSessionChange change = WatchingSessionChange.leave(dtoFixture(), 2L);

        // when
        watchingSessionRelayPublisher.publish(change);

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
            verify(remoteWatchingBroadcaster).broadcast(CONTENT_ID, change));
    }

    @Test
    @DisplayName("목적지가 페이로드와 어긋난 채팅 중계 메시지는 버려지고 다음 메시지는 정상 전달된다")
    void contentChat_destinationMismatch_isolatesFailure() {
        // given
        ContentChatDto chatDto = new ContentChatDto(
            new UserSummary(UUID.randomUUID(), "발신자", null), "안녕하세요");
        UUID mismatchedContentId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        RealtimeMessage mismatched = new RealtimeMessage(
            UUID.randomUUID(),
            "another-instance",
            ContentChatRealtimeContract.EVENT_TYPE,
            ContentChatRealtimeContract.getDestination(mismatchedContentId),
            objectMapper.valueToTree(new ContentChatRelayPayload(CONTENT_ID, chatDto)));

        // when
        sendRaw(mismatched);
        contentChatRelayPublisher.publish(CONTENT_ID, chatDto);

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
            verify(remoteChatService).broadcast(CONTENT_ID, chatDto));
        verify(remoteChatService, never())
            .broadcast(mismatchedContentId, chatDto);
    }

    private WatchingSessionDto dtoFixture() {
        return new WatchingSessionDto(
            UUID.randomUUID(),
            new UserSummary(UUID.randomUUID(), "시청자", null),
            new ContentSummary(CONTENT_ID, "movie", "제목", "설명", null, List.of(), 4.5, 10),
            Instant.now());
    }

    private void sendRaw(RealtimeMessage message) {
        try {
            stringRedisTemplate.convertAndSend(
                RealtimeChannels.MESSAGES, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
