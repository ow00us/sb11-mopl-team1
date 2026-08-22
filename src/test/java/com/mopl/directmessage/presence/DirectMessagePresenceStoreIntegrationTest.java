package com.mopl.directmessage.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.config.DirectMessagePresenceProperties;
import com.mopl.directmessage.dto.DirectMessageCreatedEvent;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.event.DirectMessageSseListener;
import com.mopl.directmessage.websocket.DirectMessageSubscriptionRegistry;
import com.mopl.global.realtime.RealtimeInstanceId;
import com.mopl.sse.service.SseEmitterManager;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {
    DirectMessagePresenceStoreIntegrationTest
        .PresenceTestConfig.class,
    DirectMessagePresenceStore.class,
    RealtimeInstanceId.class,
    RedisAutoConfiguration.class
})
@EnableConfigurationProperties(
    DirectMessagePresenceProperties.class
)
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
    "mopl.direct-message.presence.ttl=2s",
    "mopl.direct-message.presence.renew-interval=500ms"
})
class DirectMessagePresenceStoreIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(
            DockerImageName.parse("redis:7")
        )
            .withExposedPorts(6379);

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID OTHER_CONVERSATION_ID =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    private static final UUID MESSAGE_ID =
        UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

    private static final Duration TIMEOUT =
        Duration.ofSeconds(5);

    @Autowired
    private DirectMessagePresenceStore presenceStore;

    @Autowired
    private DirectMessagePresenceProperties properties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearPresence() {
        Set<String> keys =
            redisTemplate.keys(
                "mopl:presence:dm:*"
            );

        if (
            keys != null
                && !keys.isEmpty()
        ) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("DM 구독을 등록하면 해당 대화를 활성 상태로 조회")
    void register_success() {
        presenceStore.register(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        assertThat(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();

        String conversationKey =
            DirectMessagePresenceKey.conversation(
                USER_ID,
                CONVERSATION_ID
            );

        assertThat(
            redisTemplate.opsForZSet()
                .size(conversationKey)
        ).isEqualTo(1L);

        assertThat(
            redisTemplate.getExpire(
                conversationKey,
                TimeUnit.MILLISECONDS
            )
        )
            .isPositive()
            .isLessThanOrEqualTo(
                properties.getTtl().toMillis()
            );
    }

    @Test
    @DisplayName("다른 서버가 등록한 DM 활성 상태도 Redis에서 조회")
    void isActive_otherInstance_success() {
        DirectMessagePresenceStore otherInstanceStore =
            new DirectMessagePresenceStore(
                redisTemplate,
                new RealtimeInstanceId(),
                properties
            );

        otherInstanceStore.register(
            USER_ID,
            CONVERSATION_ID,
            "remote-session",
            "remote-subscription"
        );

        assertThat(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();
    }

    @Test
    @DisplayName("같은 대화의 다른 탭이 남아 있으면 활성 상태를 유지")
    void unregister_otherSessionRemains_active() {
        presenceStore.register(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        presenceStore.register(
            USER_ID,
            CONVERSATION_ID,
            "session-2",
            "subscription-2"
        );

        assertThat(
            presenceStore.unregister(
                USER_ID,
                CONVERSATION_ID,
                "session-1",
                "subscription-1"
            )
        ).isTrue();

        assertThat(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();

        assertThat(
            presenceStore.unregister(
                USER_ID,
                CONVERSATION_ID,
                "session-2",
                "subscription-2"
            )
        ).isTrue();

        assertThat(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isFalse();
    }

    @Test
    @DisplayName("연결이 종료되면 해당 세션의 모든 DM 구독을 제거")
    void unregisterSession_success() {
        presenceStore.register(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        presenceStore.register(
            USER_ID,
            OTHER_CONVERSATION_ID,
            "session-1",
            "subscription-2"
        );

        presenceStore.register(
            USER_ID,
            OTHER_CONVERSATION_ID,
            "session-2",
            "subscription-3"
        );

        assertThat(
            presenceStore.unregisterSession(
                USER_ID,
                "session-1"
            )
        ).isEqualTo(2L);

        assertThat(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isFalse();

        assertThat(
            presenceStore.isActive(
                USER_ID,
                OTHER_CONVERSATION_ID
            )
        ).isTrue();
    }

    @Test
    @DisplayName("DM 활성 상태는 TTL이 지나면 자동으로 만료")
    void isActive_ttlExpired_returnsFalse() {
        presenceStore.register(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        await()
            .atMost(TIMEOUT)
            .untilAsserted(() ->
                assertThat(
                    presenceStore.isActive(
                        USER_ID,
                        CONVERSATION_ID
                    )
                ).isFalse()
            );
    }

    @Test
    @DisplayName("정상 세션은 TTL 갱신 후 기존 만료 시점을 지나도 활성 상태를 유지")
    void renewSession_success()
        throws InterruptedException {

        presenceStore.register(
            USER_ID,
            CONVERSATION_ID,
            "session-1",
            "subscription-1"
        );

        Thread.sleep(1200L);

        assertThat(
            presenceStore.renewSession(
                USER_ID,
                "session-1"
            )
        ).isEqualTo(1L);

        Thread.sleep(1200L);

        assertThat(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isTrue();
    }

    @Test
    @DisplayName("동시 등록과 해제 후 Redis 활성 상태가 일관되게 유지")
    void registerAndUnregister_concurrent_consistent()
        throws Exception {

        runConcurrently(
            () ->
                presenceStore.register(
                    USER_ID,
                    CONVERSATION_ID,
                    "session-1",
                    "subscription-1"
                ),
            () ->
                presenceStore.register(
                    USER_ID,
                    CONVERSATION_ID,
                    "session-2",
                    "subscription-2"
                )
        );

        assertThat(
            redisTemplate.opsForZSet()
                .size(
                    DirectMessagePresenceKey
                        .conversation(
                            USER_ID,
                            CONVERSATION_ID
                        )
                )
        ).isEqualTo(2L);

        runConcurrently(
            () ->
                presenceStore.unregister(
                    USER_ID,
                    CONVERSATION_ID,
                    "session-1",
                    "subscription-1"
                ),
            () ->
                presenceStore.unregister(
                    USER_ID,
                    CONVERSATION_ID,
                    "session-2",
                    "subscription-2"
                )
        );

        assertThat(
            presenceStore.isActive(
                USER_ID,
                CONVERSATION_ID
            )
        ).isFalse();
    }

    @Test
    @DisplayName("다른 서버에서 대화를 보고 있으면 SSE를 전송하지 않고 해제 후 전송")
    void remotePresence_sseDelivery_followsActiveState() {
        DirectMessagePresenceStore otherInstanceStore =
            new DirectMessagePresenceStore(
                redisTemplate,
                new RealtimeInstanceId(),
                properties
            );

        otherInstanceStore.register(
            USER_ID,
            CONVERSATION_ID,
            "remote-session",
            "remote-subscription"
        );

        DirectMessageSubscriptionRegistry registry =
            new DirectMessageSubscriptionRegistry(
                presenceStore
            );

        SseEmitterManager sseEmitterManager =
            mock(SseEmitterManager.class);

        DirectMessageSseListener listener =
            new DirectMessageSseListener(
                registry,
                sseEmitterManager
            );

        DirectMessageDto message =
            createMessage();

        listener.sendDirectMessage(
            new DirectMessageCreatedEvent(message)
        );

        verify(
            sseEmitterManager,
            never()
        ).send(
            USER_ID,
            MESSAGE_ID,
            "direct-messages",
            message
        );

        otherInstanceStore.unregister(
            USER_ID,
            CONVERSATION_ID,
            "remote-session",
            "remote-subscription"
        );

        listener.sendDirectMessage(
            new DirectMessageCreatedEvent(message)
        );

        verify(sseEmitterManager).send(
            USER_ID,
            MESSAGE_ID,
            "direct-messages",
            message
        );
    }

    private DirectMessageDto createMessage() {
        DirectMessageDto message =
            mock(
                DirectMessageDto.class,
                Answers.RETURNS_DEEP_STUBS
            );

        when(message.id())
            .thenReturn(MESSAGE_ID);

        when(message.conversationId())
            .thenReturn(CONVERSATION_ID);

        when(message.receiver().userId())
            .thenReturn(USER_ID);

        return message;
    }

    private void runConcurrently(
        Runnable firstTask,
        Runnable secondTask
    ) throws Exception {
        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready =
            new CountDownLatch(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {
            Future<?> firstResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    firstTask.run();
                    return null;
                });

            Future<?> secondResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    secondTask.run();
                    return null;
                });

            assertThat(
                ready.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            start.countDown();

            firstResult.get(
                5,
                TimeUnit.SECONDS
            );

            secondResult.get(
                5,
                TimeUnit.SECONDS
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PresenceTestConfig {
    }
}
