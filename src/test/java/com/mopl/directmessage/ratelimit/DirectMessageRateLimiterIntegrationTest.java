package com.mopl.directmessage.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.mopl.directmessage.config.DirectMessageRateLimitProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = {
    DirectMessageRateLimiter.class,
    RedisAutoConfiguration.class
})
@EnableConfigurationProperties(
    DirectMessageRateLimitProperties.class
)
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
    "mopl.direct-message.rate-limit.max-messages=3",
    "mopl.direct-message.rate-limit.window=500ms"
})
class DirectMessageRateLimiterIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(
            DockerImageName.parse("redis:7")
        )
            .withExposedPorts(6379);

    private static final UUID SENDER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    @Autowired
    DirectMessageRateLimiter rateLimiter;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRateLimit() {
        Set<String> keys =
            redisTemplate.keys(
                "mopl:rate-limit:direct-message:*"
            );

        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("동시 DM 요청도 설정한 개수까지만 허용")
    void tryAcquire_concurrent_allowsOnlyLimit() throws Exception {
        // given
        ExecutorService executor =
            Executors.newFixedThreadPool(10);

        Callable<Boolean> request =
            () -> rateLimiter.tryAcquire(SENDER_ID);

        try {
            List<Future<Boolean>> results =
                executor.invokeAll(
                    java.util.Collections.nCopies(
                        10,
                        request
                    )
                );

            long allowedCount = 0;

            for (Future<Boolean> result : results) {
                if (result.get()) {
                    allowedCount++;
                }
            }

            // then
            assertThat(allowedCount)
                .isEqualTo(3L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("제한 시간 만료 후 DM 전송을 다시 허용")
    void tryAcquire_windowExpired_allowsAgain() {
        // given
        assertThat(rateLimiter.tryAcquire(SENDER_ID))
            .isTrue();
        assertThat(rateLimiter.tryAcquire(SENDER_ID))
            .isTrue();
        assertThat(rateLimiter.tryAcquire(SENDER_ID))
            .isTrue();
        assertThat(rateLimiter.tryAcquire(SENDER_ID))
            .isFalse();

        // when & then
        await()
            .atMost(Duration.ofSeconds(3))
            .pollInterval(Duration.ofMillis(100))
            .untilAsserted(() ->
                assertThat(
                    rateLimiter.tryAcquire(SENDER_ID)
                ).isTrue()
            );
    }
}
