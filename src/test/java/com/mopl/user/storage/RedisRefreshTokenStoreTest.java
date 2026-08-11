package com.mopl.user.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Set;
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

/**
 * RedisRefreshTokenStore와 실제 Redis의 연동을 검증하는 통합 테스트
 *
 * <p>Mockito로 저장소 호출 여부만 확인하는 단위 테스트가 아니라,
 * Testcontainers로 Redis 7 컨테이너를 실행하여 실제 Redis 명령,
 * Key-Value 저장, 사용자별 Set과 TTL 동작을 검증합니다.</p>
 *
 * <p>테스트 컨테이너는 테스트 실행 시 생성되고 테스트 종료 후 제거되므로
 * 개발자가 별도로 6379 포트의 Redis를 실행할 필요가 없습니다.</p>
 */
@DataRedisTest
@ActiveProfiles("test")
@Import(RedisRefreshTokenStore.class)
@Testcontainers
class RedisRefreshTokenStoreTest {

    /**
     * 운영 및 로컬 docker-compose.yml과 동일한 Redis 7 버전을 사용
     *
     * <p>고정된 6379 포트가 아닌 Testcontainers가 할당한 임의 포트를
     * 사용하므로 로컬 Redis가 실행 중이어도 포트가 충돌하지 않습니다.</p>
     */
    @Container
    static GenericContainer<?> redis =
        new GenericContainer<>(
            DockerImageName.parse("redis:7")
        )
            .withExposedPorts(6379);

    /**
     * Testcontainers가 할당한 Redis 주소와 포트를 Spring Data Redis에 연결
     */
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

    /**
     * 테스트 대상 저장소
     *
     * RefreshTokenStore 인터페이스로 주입받지만
     * 실제 Bean은 RedisRefreshTokenStore
     */
    @Autowired
    RefreshTokenStore refreshTokenStore;

    /**
     * 테스트 사이 Redis 데이터를 초기화하고
     * 실제 Key TTL을 확인하기 위해 사용
     */
    @Autowired
    StringRedisTemplate redisTemplate;

    /**
     * 각 테스트가 서로 저장한 Redis 데이터에 영향을 받지 않도록
     * 테스트 시작 전에 Redis DB를 초기화
     */
    @BeforeEach
    void clearRedis() {
        RedisConnection connection =
            redisTemplate.getConnectionFactory()
                .getConnection();

        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("Refresh Token 해시를 저장하고 사용자 UUID로 복원한다")
    void saveAndFindUserIdByTokenHash_success() {
        // given
        UUID userId = UUID.randomUUID();
        String tokenHash = "a".repeat(64);
        Duration expiration = Duration.ofDays(7);

        // when
        refreshTokenStore.save(
            userId,
            tokenHash,
            expiration
        );

        // then
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(tokenHash)
        )
            .contains(userId);

        assertThat(
            refreshTokenStore.findTokenHashesByUserId(userId)
        )
            .containsExactly(tokenHash);
    }

    @Test
    @DisplayName("Refresh Token 세션과 사용자별 인덱스에 동일한 TTL을 적용한다")
    void save_appliesExpirationToRedisKeys() {
        // given
        UUID userId = UUID.randomUUID();
        String tokenHash = "b".repeat(64);
        Duration expiration = Duration.ofMinutes(10);

        String sessionKey =
            "auth:refresh-token:session:" + tokenHash;

        String userSessionsKey =
            "auth:refresh-token:user:" + userId;

        // when
        refreshTokenStore.save(
            userId,
            tokenHash,
            expiration
        );

        // then
        Long sessionTtlSeconds =
            redisTemplate.getExpire(
                sessionKey,
                TimeUnit.SECONDS
            );

        Long userSessionsTtlSeconds =
            redisTemplate.getExpire(
                userSessionsKey,
                TimeUnit.SECONDS
            );

        /*
         * Redis에 명령을 실행하고 TTL을 다시 조회하는 동안 시간이 흐르므로
         * TTL이 정확히 600초인지 비교하지 않고 1초 이상 600초 이하인지 확인
         */
        assertThat(sessionTtlSeconds)
            .isPositive()
            .isLessThanOrEqualTo(expiration.toSeconds());

        assertThat(userSessionsTtlSeconds)
            .isPositive()
            .isLessThanOrEqualTo(expiration.toSeconds());
    }

    @Test
    @DisplayName("한 사용자가 여러 Refresh Token 세션을 가질 수 있다")
    void save_supportsMultipleSessionsForSameUser() {
        // given
        UUID userId = UUID.randomUUID();
        String firstTokenHash = "c".repeat(64);
        String secondTokenHash = "d".repeat(64);
        Duration expiration = Duration.ofDays(7);

        // when
        refreshTokenStore.save(
            userId,
            firstTokenHash,
            expiration
        );

        refreshTokenStore.save(
            userId,
            secondTokenHash,
            expiration
        );

        // then
        Set<String> tokenHashes =
            refreshTokenStore.findTokenHashesByUserId(userId);

        assertThat(tokenHashes)
            .containsExactlyInAnyOrder(
                firstTokenHash,
                secondTokenHash
            );

        assertThat(
            refreshTokenStore.findUserIdByTokenHash(firstTokenHash)
        )
            .contains(userId);

        assertThat(
            refreshTokenStore.findUserIdByTokenHash(secondTokenHash)
        )
            .contains(userId);
    }

    @Test
    @DisplayName("TTL이 지나면 Refresh Token 세션이 자동으로 만료된다")
    void savedSession_expiresAutomatically() {
        // given
        UUID userId = UUID.randomUUID();
        String tokenHash = "e".repeat(64);
        Duration expiration = Duration.ofMillis(300);

        refreshTokenStore.save(
            userId,
            tokenHash,
            expiration
        );

        /*
         * 저장 직후에는 Refresh Token 세션을 조회할 수 있어야 한다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(tokenHash)
        )
            .contains(userId);

        /*
         * Thread.sleep()으로 고정된 시간을 기다리는 대신 Awaitility로
         * 최대 3초 동안 Redis TTL 만료 여부를 반복 확인
         *
         * Redis와 실행 환경의 미세한 스케줄링 차이 때문에 발생할 수 있는
         * 불안정한 테스트를 방지하기 위한 방식
         */
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(() ->
                assertThat(
                    refreshTokenStore
                        .findUserIdByTokenHash(tokenHash)
                )
                    .isEmpty()
            );
    }

    @Test
    @DisplayName("존재하지 않는 Refresh Token 해시는 빈 결과를 반환한다")
    void findUserIdByTokenHash_returnsEmptyWhenSessionDoesNotExist() {
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(
                "f".repeat(64)
            )
        )
            .isEmpty();
    }

    @Test
    @DisplayName("저장에 필요한 값이 잘못되면 Redis에 저장하지 않는다")
    void save_rejectsInvalidArguments() {
        // given
        UUID userId = UUID.randomUUID();
        String tokenHash = "a".repeat(64);
        Duration expiration = Duration.ofDays(7);

        // when & then
        assertThatThrownBy(() ->
            refreshTokenStore.save(
                null,
                tokenHash,
                expiration
            )
        )
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                " ",
                expiration
            )
        )
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                tokenHash,
                Duration.ZERO
            )
        )
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                tokenHash,
                Duration.ofSeconds(-1)
            )
        )
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("짧은 세션을 추가해도 사용자 인덱스의 기존 TTL을 단축하지 않는다")
    void save_doesNotShortenUserIndexExpiration() {
        // given
        UUID userId = UUID.randomUUID();
        String longLivedTokenHash = "1".repeat(64);
        String shortLivedTokenHash = "2".repeat(64);

        Duration longExpiration =
            Duration.ofSeconds(10);

        Duration shortExpiration =
            Duration.ofMillis(300);

        /*
         * 먼저 유효기간이 긴 Refresh Token 세션을 저장
         * 사용자별 인덱스도 동일한 10초 TTL을 갖게 된다.
         */
        refreshTokenStore.save(
            userId,
            longLivedTokenHash,
            longExpiration
        );

        /*
         * 동일한 사용자에게 유효기간이 더 짧은 세션을 추가
         *
         * 이때 사용자별 인덱스 TTL을 300ms로 덮어쓰면
         * 긴 세션이 유효한 동안 사용자 인덱스가 먼저 사라지는 문제가 발생
         */
        refreshTokenStore.save(
            userId,
            shortLivedTokenHash,
            shortExpiration
        );

        /*
         * 짧은 세션이 실제로 TTL에 의해 만료될 때까지 기다린다.
         */
        await()
            .atMost(Duration.ofSeconds(3))
            .untilAsserted(() ->
                assertThat(
                    refreshTokenStore.findUserIdByTokenHash(
                        shortLivedTokenHash
                    )
                )
                    .isEmpty()
            );

        /*
         * 짧은 세션이 만료된 후에도 긴 세션은 여전히 유효해야 합니다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(
                longLivedTokenHash
            )
        )
            .contains(userId);

        /*
         * 사용자별 세션 조회에는 현재 세션 Key가 존재하는
         * 긴 세션 해시만 포함
         *
         * 이미 만료된 짧은 세션 해시는 조회 결과에서 제외
         */
        Set<String> activeTokenHashes =
            refreshTokenStore.findTokenHashesByUserId(userId);

        assertThat(activeTokenHashes)
            .containsExactly(longLivedTokenHash)
            .doesNotContain(shortLivedTokenHash);

        /*
         * 조회 과정에서 만료된 짧은 세션 해시가 사용자별 Redis Set에서도
         * 실제로 제거됐는지 확인
         */
        String userSessionsKey =
            "auth:refresh-token:user:" + userId;

        assertThat(
            redisTemplate.opsForSet()
                .members(userSessionsKey)
        )
            .containsExactly(longLivedTokenHash)
            .doesNotContain(shortLivedTokenHash);
    }
}
