package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.global.common.UserSummary;
import com.mopl.global.realtime.RealtimeChannels;
import com.mopl.global.realtime.RealtimeInstanceId;
import com.mopl.global.realtime.RealtimeMessage;
import com.mopl.global.realtime.RealtimeRelayConfig;
import com.mopl.global.realtime.RealtimeRelayPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.mopl.global.realtime.RealtimeRelayMetrics;
import com.mopl.global.realtime.RealtimeRelayStateMetrics;
import com.mopl.global.realtime.RealtimeRelaySubscriber;
import com.mopl.global.realtime.RealtimeRelaySubscriptionStarter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

@SpringBootTest(classes = {
    RealtimeRelayConfig.class,
    RealtimeRelayPublisher.class,
    RealtimeInstanceId.class,
    RealtimeRelayMetrics.class,
    RealtimeRelayStateMetrics.class,
    SimpleMeterRegistry.class,
    RealtimeRelaySubscriptionStarter.class,
    DirectMessageRelayPublisher.class,
    DirectMessageRelayHandler.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class
})
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(
    properties =
        "mopl.realtime.relay.enabled=true"
)
class DirectMessageRedisRelayIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(
            DockerImageName.parse("redis:7")
        )
            .withExposedPorts(6379);

    private static final Duration TIMEOUT =
        Duration.ofSeconds(10);

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    @Autowired
    private DirectMessageRelayPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisConnectionFactory
        redisConnectionFactory;

    @MockitoBean
    private DirectMessageBroadcaster localBroadcaster;

    private DirectMessageBroadcaster remoteBroadcaster;

    private RedisMessageListenerContainer
        remoteContainer;

    @BeforeEach
    void startRemoteInstance() {
        remoteBroadcaster =
            mock(
                DirectMessageBroadcaster.class
            );

        DirectMessageRelayHandler remoteHandler =
            new DirectMessageRelayHandler(
                objectMapper,
                remoteBroadcaster
            );

        RealtimeRelaySubscriber remoteSubscriber =
            new RealtimeRelaySubscriber(
                objectMapper,
                new RealtimeInstanceId(),
                List.of(remoteHandler),
                new RealtimeRelayMetrics(new SimpleMeterRegistry())
            );

        remoteContainer =
            new RedisMessageListenerContainer();

        remoteContainer.setConnectionFactory(
            redisConnectionFactory
        );

        remoteContainer.addMessageListener(
            remoteSubscriber,
            new ChannelTopic(
                RealtimeChannels.MESSAGES
            )
        );

        remoteContainer.afterPropertiesSet();
        remoteContainer.start();

        await()
            .atMost(TIMEOUT)
            .until(remoteContainer::isRunning);
    }

    @AfterEach
    void stopRemoteInstance()
        throws Exception {

        if (remoteContainer != null) {
            remoteContainer.destroy();
        }
    }

    @Test
    @DisplayName("Redis로 발행한 DM을 다른 서버의 WebSocket 구독자에게 전달")
    void publish_remoteInstance_broadcastsMessage() {
        // given
        DirectMessageDto message =
            createMessageDto();

        String destination =
            DirectMessageRealtimeContract.destination(
                CONVERSATION_ID
            );

        // when
        boolean published =
            publisher.publish(
                CONVERSATION_ID,
                message
            );

        // then
        assertThat(published).isTrue();

        await()
            .atMost(TIMEOUT)
            .untilAsserted(() ->
                verify(remoteBroadcaster)
                    .broadcast(
                        destination,
                        message
                    )
            );
    }

    @Test
    @DisplayName("Redis로 발행한 DM을 원본 서버에서 중복 전송하지 않음")
    void publish_originInstance_doesNotBroadcastAgain() {
        // given
        DirectMessageDto message =
            createMessageDto();

        // when
        boolean published =
            publisher.publish(
                CONVERSATION_ID,
                message
            );

        // then
        assertThat(published).isTrue();

        await()
            .atMost(TIMEOUT)
            .untilAsserted(() ->
                verify(remoteBroadcaster)
                    .broadcast(
                        DirectMessageRealtimeContract
                            .destination(
                                CONVERSATION_ID
                            ),
                        message
                    )
            );

        await()
            .during(
                Duration.ofMillis(500)
            )
            .atMost(TIMEOUT)
            .untilAsserted(() ->
                verifyNoInteractions(
                    localBroadcaster
                )
            );
    }

    @Test
    @DisplayName("같은 Redis DM messageId를 중복 수신해도 한 번만 전달")
    void receive_duplicateMessageId_broadcastsOnce() {
        // given
        DirectMessageDto message =
            createMessageDto();

        RealtimeMessage relayMessage =
            createRelayMessage(
                UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
                ),
                DirectMessageRealtimeContract.EVENT_TYPE,
                message
            );

        // when
        sendRaw(relayMessage);
        sendRaw(relayMessage);

        // then
        await()
            .during(
                Duration.ofMillis(500)
            )
            .atMost(TIMEOUT)
            .untilAsserted(() ->
                verify(
                    remoteBroadcaster,
                    times(1)
                ).broadcast(
                    DirectMessageRealtimeContract
                        .destination(
                            CONVERSATION_ID
                        ),
                    message
                )
            );
    }

    @Test
    @DisplayName("잘못된 DM Relay payload를 버리고 다음 메시지를 전달")
    void receive_invalidPayload_isolatesFailure() {
        // given
        RealtimeMessage invalidMessage =
            new RealtimeMessage(
                UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-dddddddddddd"
                ),
                "another-instance",
                DirectMessageRealtimeContract.EVENT_TYPE,
                DirectMessageRealtimeContract.destination(
                    CONVERSATION_ID
                ),
                objectMapper.valueToTree(
                    "invalid-payload"
                )
            );

        DirectMessageDto validMessage =
            createMessageDto();

        // when
        sendRaw(invalidMessage);
        sendRaw(
            createRelayMessage(
                UUID.fromString(
                    "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
                ),
                DirectMessageRealtimeContract.EVENT_TYPE,
                validMessage
            )
        );

        // then
        await()
            .atMost(TIMEOUT)
            .untilAsserted(() ->
                verify(remoteBroadcaster)
                    .broadcast(
                        DirectMessageRealtimeContract
                            .destination(
                                CONVERSATION_ID
                            ),
                        validMessage
                    )
            );
    }

    @Test
    @DisplayName("지원하지 않는 이벤트를 건너뛰고 다음 DM을 전달")
    void receive_unsupportedEvent_isIgnored() {
        // given
        DirectMessageDto message =
            createMessageDto();

        // when
        sendRaw(
            createRelayMessage(
                UUID.fromString(
                    "ffffffff-ffff-ffff-ffff-ffffffffffff"
                ),
                "notification.created",
                message
            )
        );

        sendRaw(
            createRelayMessage(
                UUID.fromString(
                    "99999999-9999-9999-9999-999999999999"
                ),
                DirectMessageRealtimeContract.EVENT_TYPE,
                message
            )
        );

        // then
        await()
            .atMost(TIMEOUT)
            .untilAsserted(() ->
                verify(
                    remoteBroadcaster,
                    times(1)
                ).broadcast(
                    DirectMessageRealtimeContract
                        .destination(
                            CONVERSATION_ID
                        ),
                    message
                )
            );
    }

    private RealtimeMessage createRelayMessage(
        UUID messageId,
        String eventType,
        DirectMessageDto message
    ) {
        return new RealtimeMessage(
            messageId,
            "another-instance",
            eventType,
            DirectMessageRealtimeContract.destination(
                CONVERSATION_ID
            ),
            objectMapper.valueToTree(message)
        );
    }

    private void sendRaw(
        RealtimeMessage relayMessage
    ) {
        try {
            stringRedisTemplate.convertAndSend(
                RealtimeChannels.MESSAGES,
                objectMapper.writeValueAsString(
                    relayMessage
                )
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                exception
            );
        }
    }

    private DirectMessageDto createMessageDto() {
        UUID senderId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        UUID receiverId =
            UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
            );

        return new DirectMessageDto(
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            ),
            CONVERSATION_ID,
            Instant.parse(
                "2026-08-20T01:00:00Z"
            ),
            new UserSummary(
                senderId,
                "발신자",
                null
            ),
            new UserSummary(
                receiverId,
                "수신자",
                null
            ),
            "실시간 메시지"
        );
    }
}
