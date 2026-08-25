package com.mopl.directmessage.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.config.DirectMessageRateLimitProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class DirectMessageRateLimiterTest {

    private static final UUID SENDER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private final StringRedisTemplate redisTemplate =
        mock(StringRedisTemplate.class);

    private DirectMessageRateLimiter rateLimiter;

    private static <T> RedisScript<T> anyScript() {
        return any(RedisScript.class);
    }

    @BeforeEach
    void setUp() {
        DirectMessageRateLimitProperties properties =
            new DirectMessageRateLimitProperties();

        properties.setMaxMessages(2);
        properties.setWindow(Duration.ofSeconds(5));

        rateLimiter =
            new DirectMessageRateLimiter(
                redisTemplate,
                properties
            );
    }

    @Test
    @DisplayName("설정한 전송 횟수 이내의 DM은 허용")
    void tryAcquire_withinLimit_allows() {
        // given
        when(
            redisTemplate.execute(
                anyScript(),
                eq(
                    List.of(
                        "mopl:rate-limit:direct-message:"
                            + SENDER_ID
                    )
                ),
                eq("5000")
            )
        ).thenReturn(2L);

        // when
        boolean result =
            rateLimiter.tryAcquire(SENDER_ID);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("설정한 전송 횟수를 초과한 DM은 제한")
    void tryAcquire_exceededLimit_denies() {
        // given
        when(
            redisTemplate.execute(
                anyScript(),
                any(),
                any(String.class)
            )
        ).thenReturn(3L);

        // when
        boolean result =
            rateLimiter.tryAcquire(SENDER_ID);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Redis 장애 시 DM 저장을 위해 전송을 허용")
    void tryAcquire_redisFailure_allows() {
        // given
        when(
            redisTemplate.execute(
                anyScript(),
                any(),
                any(String.class)
            )
        ).thenThrow(
            new RedisConnectionFailureException(
                "Redis 연결 실패"
            )
        );

        // when
        boolean result =
            rateLimiter.tryAcquire(SENDER_ID);

        // then
        assertThat(result).isTrue();
    }
}
