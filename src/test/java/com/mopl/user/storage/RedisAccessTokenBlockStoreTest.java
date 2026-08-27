package com.mopl.user.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataRedisTest
@ActiveProfiles("test")
@Import(RedisAccessTokenBlockStore.class)
@Testcontainers
class RedisAccessTokenBlockStoreTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final String BLOCK_KEY =
        "auth:access-token:blocked-user:"
            + USER_ID;

    @Container
    static GenericContainer<?> redis =
        new GenericContainer<>(
            DockerImageName.parse("redis:7")
        )
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedisProperties(
        DynamicPropertyRegistry registry
    ) {
        registry.add(
            "spring.data.redis.host",
            redis::getHost
        );

        registry.add(
            "spring.data.redis.port",
            redis::getFirstMappedPort
        );
    }

    @Autowired
    AccessTokenBlockStore blockStore;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRedis() {
        RedisConnection connection =
            redisTemplate
                .getConnectionFactory()
                .getConnection();

        try {
            connection.serverCommands()
                .flushDb();
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("사용자 Access Token 차단 상태를 TTL과 함께 저장한다")
    void block_storesBlockedStateWithTtl() {
        // given
        Duration expiration =
            Duration.ofHours(3);

        // when
        blockStore.block(
            USER_ID,
            expiration
        );

        // then
        assertThat(
            redisTemplate.opsForValue()
                .get(BLOCK_KEY)
        ).isEqualTo("1");

        Long ttl =
            redisTemplate.getExpire(
                BLOCK_KEY,
                TimeUnit.MILLISECONDS
            );

        assertThat(ttl)
            .isPositive()
            .isLessThanOrEqualTo(
                expiration.toMillis()
            );
    }

    @Test
    @DisplayName("저장된 차단 상태를 조회한다")
    void isBlocked_returnsStoredState() {
        // given
        assertThat(
            blockStore.isBlocked(USER_ID)
        ).isFalse();

        blockStore.block(
            USER_ID,
            Duration.ofHours(3)
        );

        // when
        boolean blocked =
            blockStore.isBlocked(USER_ID);

        // then
        assertThat(blocked).isTrue();
    }

    @Test
    @DisplayName("사용자의 Access Token 차단 상태를 제거한다")
    void unblock_deletesBlockedState() {
        // given
        blockStore.block(
            USER_ID,
            Duration.ofHours(3)
        );

        // when
        blockStore.unblock(USER_ID);

        // then
        assertThat(
            blockStore.isBlocked(USER_ID)
        ).isFalse();

        assertThat(
            redisTemplate.hasKey(BLOCK_KEY)
        ).isFalse();
    }

    @Test
    @DisplayName("차단 만료 시간이 양수가 아니면 저장하지 않는다")
    void block_rejectsNonPositiveExpiration() {
        assertThatThrownBy(() ->
            blockStore.block(
                USER_ID,
                Duration.ZERO
            )
        )
            .isInstanceOf(
                IllegalArgumentException.class
            )
            .hasMessage(
                "Access Token 차단 만료 시간은 양수여야 합니다."
            );
    }

    @Test
    @DisplayName("사용자 UUID가 없으면 차단 상태를 처리하지 않는다")
    void block_rejectsMissingUserId() {
        assertThatThrownBy(() ->
            blockStore.block(
                null,
                Duration.ofHours(3)
            )
        )
            .isInstanceOf(
                IllegalArgumentException.class
            )
            .hasMessage(
                "사용자 UUID는 필수입니다."
            );
    }
}
