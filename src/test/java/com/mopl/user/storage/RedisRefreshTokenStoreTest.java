package com.mopl.user.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    @Test
    @DisplayName("기존 Refresh Token을 새로운 Refresh Token으로 원자적으로 교체한다")
    void rotate_success() {
        // given
        UUID userId = UUID.randomUUID();
        String oldTokenHash = "old-token-hash";
        String newTokenHash = "new-token-hash";
        Duration expiration = Duration.ofMinutes(30);

        /*
         * 교체 대상이 되는 기존 Refresh Token 세션을 Redis에 저장
         */
        refreshTokenStore.save(
            userId,
            oldTokenHash,
            expiration
        );

        // when
        boolean rotated = refreshTokenStore.rotate(
            userId,
            oldTokenHash,
            newTokenHash,
            expiration
        );

        // then
        assertThat(rotated).isTrue();

        /*
         * Rotation이 성공하면 기존 세션 Key는 제거
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(oldTokenHash)
        ).isEmpty();

        /*
         * 새로 발급한 Refresh Token 해시에는 기존 사용자 UUID가
         * 연결되어 있어야 한다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(newTokenHash)
        ).contains(userId);

        /*
         * 사용자별 세션 인덱스에서도 기존 해시는 제거되고
         * 새로운 해시만 남아 있어야 한다.
         */
        assertThat(
            refreshTokenStore.findTokenHashesByUserId(userId)
        ).containsExactly(newTokenHash);
    }

    @Test
    @DisplayName("이미 Rotation에 사용한 Refresh Token은 다시 사용할 수 없다")
    void rotate_failWhenOldTokenIsReused() {
        // given
        UUID userId = UUID.randomUUID();
        String oldTokenHash = "old-token-hash";
        String firstNewTokenHash = "first-new-token-hash";
        String secondNewTokenHash = "second-new-token-hash";
        Duration expiration = Duration.ofMinutes(30);

        refreshTokenStore.save(
            userId,
            oldTokenHash,
            expiration
        );

        /*
         * 첫 번째 재발급 요청에서 기존 Refresh Token을 정상적으로 소비
         */
        boolean firstRotation = refreshTokenStore.rotate(
            userId,
            oldTokenHash,
            firstNewTokenHash,
            expiration
        );

        // when
        /*
         * 이미 소비된 기존 Refresh Token으로 다시 재발급을 시도
         */
        boolean secondRotation = refreshTokenStore.rotate(
            userId,
            oldTokenHash,
            secondNewTokenHash,
            expiration
        );

        // then
        assertThat(firstRotation).isTrue();
        assertThat(secondRotation).isFalse();

        /*
         * 첫 번째 요청에서 발급한 세션은 그대로 유지되어야 한다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(firstNewTokenHash)
        ).contains(userId);

        /*
         * 실패한 두 번째 요청의 토큰은 저장되면 안 된다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(secondNewTokenHash)
        ).isEmpty();

        assertThat(
            refreshTokenStore.findTokenHashesByUserId(userId)
        ).containsExactly(firstNewTokenHash);
    }

    @Test
    @DisplayName("Refresh Token 소유자가 다르면 Rotation에 실패한다")
    void rotate_failWhenUserDoesNotOwnToken() {
        // given
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        String oldTokenHash = "owner-old-token-hash";
        String newTokenHash = "other-user-new-token-hash";
        Duration expiration = Duration.ofMinutes(30);

        refreshTokenStore.save(
            ownerId,
            oldTokenHash,
            expiration
        );

        // when
        boolean rotated = refreshTokenStore.rotate(
            otherUserId,
            oldTokenHash,
            newTokenHash,
            expiration
        );

        // then
        assertThat(rotated).isFalse();

        /*
         * 소유자가 일치하지 않았으므로 기존 Refresh Token 세션은
         * 삭제되지 않고 그대로 유지
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(oldTokenHash)
        ).contains(ownerId);

        /*
         * 권한이 없는 요청에서 전달한 새로운 토큰은 저장되지 않아야 한다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(newTokenHash)
        ).isEmpty();

        assertThat(
            refreshTokenStore.findTokenHashesByUserId(ownerId)
        ).containsExactly(oldTokenHash);

        assertThat(
            refreshTokenStore.findTokenHashesByUserId(otherUserId)
        ).isEmpty();
    }

    @Test
    @DisplayName("같은 Refresh Token의 동시 Rotation 요청은 하나만 성공한다")
    void rotate_onlyOneRequestSucceedsWhenCalledConcurrently()
        throws Exception {

        // given
        UUID userId = UUID.randomUUID();
        String oldTokenHash = "concurrent-old-token-hash";
        String firstNewTokenHash = "concurrent-first-new-token-hash";
        String secondNewTokenHash = "concurrent-second-new-token-hash";
        Duration expiration = Duration.ofMinutes(30);

        refreshTokenStore.save(
            userId,
            oldTokenHash,
            expiration
        );

        /*
         * 두 요청을 서로 다른 스레드에서 실행하기 위한 스레드 풀
         */
        ExecutorService executor = Executors.newFixedThreadPool(2);

        /*
         * 두 작업이 모두 실행 준비를 마칠 때까지 기다리는 용도
         */
        CountDownLatch ready = new CountDownLatch(2);

        /*
         * 두 작업을 가능한 한 같은 시점에 시작시키기 위한 신호
         */
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> firstResult = executor.submit(() -> {
                ready.countDown();
                start.await();

                return refreshTokenStore.rotate(
                    userId,
                    oldTokenHash,
                    firstNewTokenHash,
                    expiration
                );
            });

            Future<Boolean> secondResult = executor.submit(() -> {
                ready.countDown();
                start.await();

                return refreshTokenStore.rotate(
                    userId,
                    oldTokenHash,
                    secondNewTokenHash,
                    expiration
                );
            });

            /*
             * 두 스레드가 모두 시작 신호를 기다리는 상태가 됐는지 확인
             */
            assertThat(
                ready.await(5, TimeUnit.SECONDS)
            ).isTrue();

            /*
             * 두 Rotation 요청을 동시에 시작
             */
            start.countDown();

            boolean firstSucceeded =
                firstResult.get(5, TimeUnit.SECONDS);

            boolean secondSucceeded =
                secondResult.get(5, TimeUnit.SECONDS);

            assertThat(
                firstSucceeded ^ secondSucceeded
            ).isTrue();

            String successfulTokenHash =
                firstSucceeded
                    ? firstNewTokenHash
                    : secondNewTokenHash;

            String failedTokenHash =
                firstSucceeded
                    ? secondNewTokenHash
                    : firstNewTokenHash;

            /*
             * 기존 Refresh Token은 성공한 요청에서 소비
             */
            assertThat(
                refreshTokenStore.findUserIdByTokenHash(oldTokenHash)
            ).isEmpty();

            /*
             * 경쟁에서 성공한 요청의 새 토큰만 저장
             */
            assertThat(
                refreshTokenStore.findUserIdByTokenHash(
                    successfulTokenHash
                )
            ).contains(userId);

            /*
             * 경쟁에서 실패한 요청의 새 토큰은 저장되면 안 된다.
             */
            assertThat(
                refreshTokenStore.findUserIdByTokenHash(
                    failedTokenHash
                )
            ).isEmpty();

            assertThat(
                refreshTokenStore.findTokenHashesByUserId(userId)
            ).containsExactly(successfulTokenHash);
        } finally {
            /*
             * 테스트 성공 여부와 관계없이 생성한 스레드를 정리
             */
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Refresh Token 세션과 사용자별 인덱스를 함께 폐기한다")
    void revoke_success() {
        // given
        UUID userId = UUID.randomUUID();
        String revokedTokenHash = "revoked-token-hash";
        String remainingTokenHash = "remaining-token-hash";
        Duration expiration = Duration.ofMinutes(30);

        /*
         * 같은 사용자가 두 개의 기기에서 로그인한 상황을 구성
         *
         * 이번 로그아웃에서는 revokedTokenHash에 해당하는 현재 기기
         * 세션만 삭제하고, 다른 기기의 remainingTokenHash 세션은
         * 그대로 유지되어야 한다.
         */
        refreshTokenStore.save(
            userId,
            revokedTokenHash,
            expiration
        );

        refreshTokenStore.save(
            userId,
            remainingTokenHash,
            expiration
        );

        // when
        boolean revoked = refreshTokenStore.revoke(
            userId,
            revokedTokenHash
        );

        // then
        assertThat(revoked).isTrue();

        /*
         * 로그아웃에 사용한 Refresh Token 세션 Key는 삭제되어
         * 더 이상 사용자 UUID를 조회할 수 없어야 한다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(
                revokedTokenHash
            )
        ).isEmpty();

        /*
         * 현재 기기 로그아웃은 다른 기기의 Refresh Token 세션까지
         * 삭제하는 전체 로그아웃이 아니므로 나머지 세션은 유지
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(
                remainingTokenHash
            )
        ).contains(userId);

        /*
         * 사용자별 세션 인덱스에서도 폐기한 토큰 해시만 제거되고
         * 아직 유효한 다른 세션 해시는 유지
         */
        assertThat(
            refreshTokenStore.findTokenHashesByUserId(userId)
        ).containsExactly(remainingTokenHash);
    }

    @Test
    @DisplayName("사용자의 마지막 Refresh Token을 폐기하면 세션 목록이 비워진다")
    void revoke_removesLastUserSession() {
        // given
        UUID userId = UUID.randomUUID();
        String tokenHash = "last-token-hash";
        Duration expiration = Duration.ofMinutes(30);

        refreshTokenStore.save(
            userId,
            tokenHash,
            expiration
        );

        // when
        boolean revoked = refreshTokenStore.revoke(
            userId,
            tokenHash
        );

        // then
        assertThat(revoked).isTrue();

        /*
         * 마지막 세션이 폐기되면 개별 세션과 사용자별 세션 목록이
         * 모두 비어 있어야 한다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(tokenHash)
        ).isEmpty();

        assertThat(
            refreshTokenStore.findTokenHashesByUserId(userId)
        ).isEmpty();
    }

    @Test
    @DisplayName("이미 없거나 폐기된 Refresh Token 세션은 다시 폐기하지 않는다")
    void revoke_returnsFalseWhenSessionDoesNotExist() {
        // given
        UUID userId = UUID.randomUUID();
        String tokenHash = "missing-token-hash";

        // when
        boolean revoked = refreshTokenStore.revoke(
            userId,
            tokenHash
        );

        // then
        /*
         * 로그아웃 Service에서는 false도 멱등한 로그아웃 성공으로
         * 처리할 예정이지만, 저장소는 실제 삭제 여부를 구분해 반환
         */
        assertThat(revoked).isFalse();

        assertThat(
            refreshTokenStore.findUserIdByTokenHash(tokenHash)
        ).isEmpty();

        assertThat(
            refreshTokenStore.findTokenHashesByUserId(userId)
        ).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자가 소유한 Refresh Token 세션은 폐기하지 않는다")
    void revoke_returnsFalseWhenUserDoesNotOwnToken() {
        // given
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        String tokenHash = "owner-token-hash";
        Duration expiration = Duration.ofMinutes(30);

        refreshTokenStore.save(
            ownerId,
            tokenHash,
            expiration
        );

        // when
        boolean revoked = refreshTokenStore.revoke(
            otherUserId,
            tokenHash
        );

        // then
        assertThat(revoked).isFalse();

        /*
         * 인증된 사용자 UUID와 세션 소유자가 다르면
         * 기존 세션 Key를 삭제하면 안 된다.
         */
        assertThat(
            refreshTokenStore.findUserIdByTokenHash(tokenHash)
        ).contains(ownerId);

        /*
         * 실제 소유자의 사용자별 인덱스도 변경되지 않아야 한다.
         */
        assertThat(
            refreshTokenStore.findTokenHashesByUserId(ownerId)
        ).containsExactly(tokenHash);

        /*
         * 로그아웃을 요청한 다른 사용자의 세션 인덱스에는
         * 새로운 값이 생기면 안 된다.
         */
        assertThat(
            refreshTokenStore.findTokenHashesByUserId(otherUserId)
        ).isEmpty();
    }

    @Test
    @DisplayName("Refresh Token 세션 폐기 인자가 올바르지 않으면 요청을 거부한다")
    void revoke_rejectsInvalidArguments() {
        // given
        UUID userId = UUID.randomUUID();
        String tokenHash = "valid-token-hash";

        // when & then
        assertThatThrownBy(() ->
            refreshTokenStore.revoke(
                null,
                tokenHash
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Refresh Token 사용자 UUID는 null일 수 없습니다."
            );

        assertThatThrownBy(() ->
            refreshTokenStore.revoke(
                userId,
                null
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "폐기할 Refresh Token 해시는 비어 있을 수 없습니다."
            );

        assertThatThrownBy(() ->
            refreshTokenStore.revoke(
                userId,
                "   "
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "폐기할 Refresh Token 해시는 비어 있을 수 없습니다."
            );
    }
}
