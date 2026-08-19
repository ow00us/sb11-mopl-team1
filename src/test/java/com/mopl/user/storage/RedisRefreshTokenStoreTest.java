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
    @DisplayName("Refresh Token Family를 저장하고 Family ID와 현재 해시로 사용자를 조회한다")
    void saveAndFindUserIdByFamilyAndTokenHash_success() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String tokenHash = "a".repeat(64);
        Duration expiration = Duration.ofMinutes(30);

        // when
        refreshTokenStore.save(
            userId,
            familyId,
            tokenHash,
            expiration
        );

        // then
        /*
         * Family ID와 현재 활성 tokenHash가 모두 일치하면
         * 해당 Refresh Token Family를 소유한 사용자 UUID를 반환
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    tokenHash
                )
        ).contains(userId);

        /*
         * Redis Family Hash에는 Refresh Token 원문이 아닌
         * 사용자 UUID와 SHA-256 해시만 저장
         */
        String familyKey =
            "auth:refresh-token:family:"
                + familyId;

        assertThat(
            redisTemplate.opsForHash()
                .get(familyKey, "userId")
        ).isEqualTo(userId.toString());

        assertThat(
            redisTemplate.opsForHash()
                .get(familyKey, "tokenHash")
        ).isEqualTo(tokenHash);

        /*
         * 사용자별 인덱스에는 tokenHash가 아니라
         * Rotation 전후에도 유지되는 Family ID를 저장
         */
        String userFamiliesKey =
            "auth:refresh-token:user:"
                + userId;

        assertThat(
            redisTemplate.opsForSet()
                .members(userFamiliesKey)
        ).containsExactly(familyId.toString());
    }

    @Test
    @DisplayName("Refresh Token Family 세션과 사용자별 인덱스에 TTL을 적용한다")
    void save_appliesExpirationToRedisKeys() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String tokenHash = "b".repeat(64);
        Duration expiration = Duration.ofSeconds(30);

        // when
        refreshTokenStore.save(
            userId,
            familyId,
            tokenHash,
            expiration
        );

        // then
        String familyKey =
            "auth:refresh-token:family:"
                + familyId;

        String userFamiliesKey =
            "auth:refresh-token:user:"
                + userId;

        Long familyTtl =
            redisTemplate.getExpire(
                familyKey,
                TimeUnit.MILLISECONDS
            );

        Long userIndexTtl =
            redisTemplate.getExpire(
                userFamiliesKey,
                TimeUnit.MILLISECONDS
            );

        /*
         * 명령 실행 시간이 조금 흐르기 때문에 TTL이 정확히 30초가 아니라
         * 30초보다 약간 작을 수 있다.
         */
        assertThat(familyTtl)
            .isPositive()
            .isLessThanOrEqualTo(
                expiration.toMillis()
            );

        assertThat(userIndexTtl)
            .isPositive()
            .isLessThanOrEqualTo(
                expiration.toMillis()
            );
    }

    @Test
    @DisplayName("한 사용자가 서로 다른 여러 Refresh Token Family를 가질 수 있다")
    void save_supportsMultipleFamiliesForSameUser() {
        // given
        UUID userId = UUID.randomUUID();

        UUID firstFamilyId =
            UUID.randomUUID();

        UUID secondFamilyId =
            UUID.randomUUID();

        String firstTokenHash =
            "c".repeat(64);

        String secondTokenHash =
            "d".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        // when
        refreshTokenStore.save(
            userId,
            firstFamilyId,
            firstTokenHash,
            expiration
        );

        refreshTokenStore.save(
            userId,
            secondFamilyId,
            secondTokenHash,
            expiration
        );

        // then
        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(userId)
        ).containsExactlyInAnyOrder(
            firstFamilyId,
            secondFamilyId
        );

        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    firstFamilyId,
                    firstTokenHash
                )
        ).contains(userId);

        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    secondFamilyId,
                    secondTokenHash
                )
        ).contains(userId);
    }

    @Test
    @DisplayName("Family Key의 TTL이 지나면 Refresh Token 세션이 자동으로 만료된다")
    void savedFamily_expiresAutomatically() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String tokenHash = "e".repeat(64);

        refreshTokenStore.save(
            userId,
            familyId,
            tokenHash,
            Duration.ofMillis(100)
        );

        // when & then
        /*
         * Redis TTL 만료는 비동기로 반영될 수 있으므로
         * 즉시 단언하지 않고 Awaitility로 최대 3초 동안 확인
         */
        await()
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(
                    refreshTokenStore
                        .findUserIdByFamilyAndTokenHash(
                            familyId,
                            tokenHash
                        )
                ).isEmpty();
            });

        /*
         * 사용자별 Set의 Member에는 개별 TTL을 설정할 수 없다.
         * findFamilyIdsByUserId()가 만료된 Family Key를 확인하고
         * 죽은 Family ID를 사용자 인덱스에서 정리
         */
        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(userId)
        ).doesNotContain(familyId);
    }

    @Test
    @DisplayName("Family ID 또는 현재 활성 해시가 일치하지 않으면 빈 결과를 반환한다")
    void findUserIdByFamilyAndTokenHash_returnsEmptyWhenSessionDoesNotMatch() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        String activeTokenHash =
            "f".repeat(64);

        String differentTokenHash =
            "0".repeat(64);

        refreshTokenStore.save(
            userId,
            familyId,
            activeTokenHash,
            Duration.ofMinutes(30)
        );

        // when & then
        /*
         * Family ID는 맞지만 해시가 다르면 이전 Token 또는 위조된 Token이므로
         * 사용자 인증 정보를 반환하지 않는다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    differentTokenHash
                )
        ).isEmpty();

        /*
         * 존재하지 않는 Family ID도 빈 결과를 반환
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    UUID.randomUUID(),
                    activeTokenHash
                )
        ).isEmpty();

        /*
         * 잘못된 조회 인자는 Redis 명령을 실행하지 않고 빈 결과로 처리
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    null,
                    activeTokenHash
                )
        ).isEmpty();

        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    "invalid-hash"
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("동일한 Family ID로 기존 세션을 덮어쓸 수 없다")
    void save_rejectsDuplicatedFamilyId() {
        // given
        UUID firstUserId =
            UUID.randomUUID();

        UUID secondUserId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String firstTokenHash =
            "1".repeat(64);

        String secondTokenHash =
            "2".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        refreshTokenStore.save(
            firstUserId,
            familyId,
            firstTokenHash,
            expiration
        );

        // when & then
        assertThatThrownBy(() ->
            refreshTokenStore.save(
                secondUserId,
                familyId,
                secondTokenHash,
                expiration
            )
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessage(
                "Refresh Token Family 세션을 저장하지 못했습니다."
            );

        /*
         * Family ID 충돌이 발생해도 기존 세션의 소유자와
         * 활성 tokenHash가 덮어써지지 않아야 한다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    firstTokenHash
                )
        ).contains(firstUserId);

        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    secondTokenHash
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("Family 저장에 필요한 값이 잘못되면 Redis에 저장하지 않는다")
    void save_rejectsInvalidArguments() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String validTokenHash = "3".repeat(64);
        Duration expiration = Duration.ofMinutes(30);

        // when & then
        assertThatThrownBy(() ->
            refreshTokenStore.save(
                null,
                familyId,
                validTokenHash,
                expiration
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                null,
                validTokenHash,
                expiration
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                familyId,
                null,
                expiration
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                familyId,
                "invalid-hash",
                expiration
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                familyId,
                validTokenHash,
                null
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                familyId,
                validTokenHash,
                Duration.ZERO
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.save(
                userId,
                familyId,
                validTokenHash,
                Duration.ofNanos(1)
            )
        ).isInstanceOf(IllegalArgumentException.class);

        /*
         * 모든 잘못된 저장 요청 이후에도 Redis에는
         * Family 세션이나 사용자 인덱스가 생성되지 않아야 한다.
         */
        assertThat(
            redisTemplate.keys(
                "auth:refresh-token:*"
            )
        ).isEmpty();
    }

    @Test
    @DisplayName("짧은 Family를 추가해도 사용자 인덱스의 기존 TTL을 단축하지 않는다")
    void save_doesNotShortenUserIndexExpiration() {
        // given
        UUID userId = UUID.randomUUID();

        UUID longFamilyId =
            UUID.randomUUID();

        UUID shortFamilyId =
            UUID.randomUUID();

        String longTokenHash =
            "4".repeat(64);

        String shortTokenHash =
            "5".repeat(64);

        /*
         * 첫 번째 Family는 10초, 두 번째 Family는 1초 동안 유지
         *
         * 사용자 인덱스의 TTL은 새 Family의 TTL로 무조건 덮어쓰지 않고,
         * 현재 TTL과 새 TTL 중 더 긴 값을 유지해야 한다.
         */
        refreshTokenStore.save(
            userId,
            longFamilyId,
            longTokenHash,
            Duration.ofSeconds(10)
        );

        // when
        refreshTokenStore.save(
            userId,
            shortFamilyId,
            shortTokenHash,
            Duration.ofSeconds(1)
        );

        // then
        String userFamiliesKey =
            "auth:refresh-token:user:"
                + userId;

        Long userIndexTtl =
            redisTemplate.getExpire(
                userFamiliesKey,
                TimeUnit.MILLISECONDS
            );

        /*
         * 짧은 Family의 1초로 사용자 인덱스 TTL이 줄었다면
         * 긴 Family가 살아 있어도 사용자별 세션 목록을 잃게 된다.
         *
         * 명령 실행 시간을 고려해 7초보다 긴 TTL이 남아 있는지만 확인
         */
        assertThat(userIndexTtl)
            .isGreaterThan(
                Duration.ofSeconds(7)
                    .toMillis()
            );

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(userId)
        ).containsExactlyInAnyOrder(
            longFamilyId,
            shortFamilyId
        );

        /*
         * 짧은 Family Key가 만료될 때까지 기다린다.
         */
        await()
            .atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                assertThat(
                    refreshTokenStore
                        .findUserIdByFamilyAndTokenHash(
                            shortFamilyId,
                            shortTokenHash
                        )
                ).isEmpty();
            });

        /*
         * 조회 시 만료된 Family ID는 Set에서 제거되고
         * 아직 유효한 긴 Family만 반환되어야 한다.
         */
        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(userId)
        ).containsExactly(longFamilyId);

        assertThat(
            redisTemplate.opsForSet()
                .members(userFamiliesKey)
        ).containsExactly(
            longFamilyId.toString()
        );
    }

    @Test
    @DisplayName("같은 Family의 활성 Refresh Token 해시를 원자적으로 교체한다")
    void rotate_success() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        String oldTokenHash =
            "6".repeat(64);

        String newTokenHash =
            "7".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        refreshTokenStore.save(
            userId,
            familyId,
            oldTokenHash,
            expiration
        );

        // when
        boolean rotated =
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                newTokenHash,
                expiration
            );

        // then
        assertThat(rotated).isTrue();

        /*
         * Rotation 이후 기존 Token 해시는 더 이상 인증에 사용할 수 없다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    oldTokenHash
                )
        ).isEmpty();

        /*
         * 새 Token 해시만 현재 활성 Token으로 인정
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    newTokenHash
                )
        ).contains(userId);

        /*
         * Rotation은 새로운 Family를 만드는 작업이 아님.
         * 로그아웃에서 사용할 Family ID는 그대로 유지되어야 함.
         */
        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(userId)
        ).containsExactly(familyId);

        String familyKey =
            "auth:refresh-token:family:"
                + familyId;

        assertThat(
            redisTemplate.opsForHash()
                .get(familyKey, "tokenHash")
        ).isEqualTo(newTokenHash);
    }

    @Test
    @DisplayName("이미 Rotation에 사용한 기존 Refresh Token은 다시 사용할 수 없다")
    void rotate_failWhenOldTokenIsReused() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        String oldTokenHash =
            "8".repeat(64);

        String firstNewTokenHash =
            "9".repeat(64);

        String secondNewTokenHash =
            "a".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        refreshTokenStore.save(
            userId,
            familyId,
            oldTokenHash,
            expiration
        );

        boolean firstRotation =
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                firstNewTokenHash,
                expiration
            );

        // when
        boolean reusedOldTokenRotation =
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                secondNewTokenHash,
                expiration
            );

        // then
        assertThat(firstRotation).isTrue();
        assertThat(reusedOldTokenRotation).isFalse();

        /*
         * 실패한 두 번째 Rotation이 현재 활성 해시를 덮어쓰면 안된다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    firstNewTokenHash
                )
        ).contains(userId);

        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    secondNewTokenHash
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("Refresh Token Family 소유자가 다르면 Rotation에 실패한다")
    void rotate_failWhenUserDoesNotOwnFamily() {
        // given
        UUID ownerUserId =
            UUID.randomUUID();

        UUID otherUserId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String oldTokenHash =
            "b".repeat(64);

        String newTokenHash =
            "c".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        refreshTokenStore.save(
            ownerUserId,
            familyId,
            oldTokenHash,
            expiration
        );

        // when
        boolean rotated =
            refreshTokenStore.rotate(
                otherUserId,
                familyId,
                oldTokenHash,
                newTokenHash,
                expiration
            );

        // then
        assertThat(rotated).isFalse();

        /*
         * 다른 사용자의 요청으로 기존 Family 세션이 변경되지 않아야 한다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    oldTokenHash
                )
        ).contains(ownerUserId);

        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    newTokenHash
                )
        ).isEmpty();

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(otherUserId)
        ).isEmpty();
    }

    @Test
    @DisplayName("같은 Refresh Token의 동시 Rotation 요청은 하나만 성공한다")
    void rotate_onlyOneRequestSucceedsWhenCalledConcurrently()
        throws Exception {

        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        String oldTokenHash =
            "d".repeat(64);

        String firstNewTokenHash =
            "e".repeat(64);

        String secondNewTokenHash =
            "f".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        refreshTokenStore.save(
            userId,
            familyId,
            oldTokenHash,
            expiration
        );

        /*
         * 두 작업 스레드가 모두 준비된 뒤 동시에 Rotation을 요청하도록
         * 시작 신호용 CountDownLatch를 사용
         */
        CountDownLatch readySignal =
            new CountDownLatch(2);

        CountDownLatch startSignal =
            new CountDownLatch(1);

        ExecutorService executorService =
            Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> firstResult =
                executorService.submit(() -> {
                    readySignal.countDown();
                    startSignal.await();

                    return refreshTokenStore.rotate(
                        userId,
                        familyId,
                        oldTokenHash,
                        firstNewTokenHash,
                        expiration
                    );
                });

            Future<Boolean> secondResult =
                executorService.submit(() -> {
                    readySignal.countDown();
                    startSignal.await();

                    return refreshTokenStore.rotate(
                        userId,
                        familyId,
                        oldTokenHash,
                        secondNewTokenHash,
                        expiration
                    );
                });

            // when
            assertThat(
                readySignal.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            startSignal.countDown();

            boolean firstSucceeded =
                firstResult.get(
                    5,
                    TimeUnit.SECONDS
                );

            boolean secondSucceeded =
                secondResult.get(
                    5,
                    TimeUnit.SECONDS
                );

            // then
            int successCount =
                (firstSucceeded ? 1 : 0)
                    + (secondSucceeded ? 1 : 0);

            /*
             * Redis Lua Script는 기존 해시 확인과 새 해시 교체를
             * 하나의 원자적인 연산으로 실행
             *
             * 따라서 같은 기존 Token을 사용한 두 요청 중
             * 정확히 하나만 성공
             */
            assertThat(successCount).isEqualTo(1);

            assertThat(
                refreshTokenStore
                    .findUserIdByFamilyAndTokenHash(
                        familyId,
                        oldTokenHash
                    )
            ).isEmpty();

            boolean firstHashIsActive =
                refreshTokenStore
                    .findUserIdByFamilyAndTokenHash(
                        familyId,
                        firstNewTokenHash
                    )
                    .isPresent();

            boolean secondHashIsActive =
                refreshTokenStore
                    .findUserIdByFamilyAndTokenHash(
                        familyId,
                        secondNewTokenHash
                    )
                    .isPresent();

            /*
             * 성공한 요청이 발급한 해시 하나만 활성 상태여야 함.
             */
            assertThat(
                firstHashIsActive
                    ^ secondHashIsActive
            ).isTrue();

            assertThat(firstHashIsActive)
                .isEqualTo(firstSucceeded);

            assertThat(secondHashIsActive)
                .isEqualTo(secondSucceeded);
        } finally {
            /*
             * 테스트 성공·실패와 관계없이 작업 스레드를 종료
             */
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("Rotation 인자가 올바르지 않으면 요청을 거부한다")
    void rotate_rejectsInvalidArguments() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        String oldTokenHash =
            "1".repeat(64);

        String newTokenHash =
            "2".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        // when & then
        assertThatThrownBy(() ->
            refreshTokenStore.rotate(
                null,
                familyId,
                oldTokenHash,
                newTokenHash,
                expiration
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.rotate(
                userId,
                null,
                oldTokenHash,
                newTokenHash,
                expiration
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.rotate(
                userId,
                familyId,
                "invalid-old-hash",
                newTokenHash,
                expiration
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                "invalid-new-hash",
                expiration
            )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                oldTokenHash,
                expiration
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "기존 Refresh Token 해시와 새로운 해시는 달라야 합니다."
            );

        assertThatThrownBy(() ->
            refreshTokenStore.rotate(
                userId,
                familyId,
                oldTokenHash,
                newTokenHash,
                Duration.ZERO
            )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Refresh Token Family와 사용자별 인덱스를 함께 폐기한다")
    void revoke_success() {
        // given
        UUID userId = UUID.randomUUID();

        UUID revokedFamilyId =
            UUID.randomUUID();

        UUID remainingFamilyId =
            UUID.randomUUID();

        String revokedTokenHash =
            "3".repeat(64);

        String remainingTokenHash =
            "4".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        refreshTokenStore.save(
            userId,
            revokedFamilyId,
            revokedTokenHash,
            expiration
        );

        refreshTokenStore.save(
            userId,
            remainingFamilyId,
            remainingTokenHash,
            expiration
        );

        // when
        boolean revoked =
            refreshTokenStore.revoke(
                userId,
                revokedFamilyId
            );

        // then
        assertThat(revoked).isTrue();

        /*
         * 폐기한 Family Key는 Redis에서 제거되어
         * 기존 Refresh Token을 더 이상 사용할 수 없어야 한다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    revokedFamilyId,
                    revokedTokenHash
                )
        ).isEmpty();

        /*
         * 사용자에게 다른 로그인 Family가 남아 있다면
         * 해당 Family는 영향을 받지 않아야 한다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    remainingFamilyId,
                    remainingTokenHash
                )
        ).contains(userId);

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(userId)
        ).containsExactly(remainingFamilyId);

        String revokedFamilyKey =
            "auth:refresh-token:family:"
                + revokedFamilyId;

        assertThat(
            redisTemplate.hasKey(revokedFamilyKey)
        ).isFalse();
    }

    @Test
    @DisplayName("사용자의 마지막 Refresh Token Family를 폐기하면 사용자 인덱스도 삭제한다")
    void revoke_removesLastUserFamilyIndex() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        String tokenHash =
            "5".repeat(64);

        refreshTokenStore.save(
            userId,
            familyId,
            tokenHash,
            Duration.ofMinutes(30)
        );

        String userFamiliesKey =
            "auth:refresh-token:user:"
                + userId;

        assertThat(
            redisTemplate.hasKey(userFamiliesKey)
        ).isTrue();

        // when
        boolean revoked =
            refreshTokenStore.revoke(
                userId,
                familyId
            );

        // then
        assertThat(revoked).isTrue();

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(userId)
        ).isEmpty();

        /*
         * 마지막 Set Member를 제거한 뒤 빈 Set Key도 삭제하여
         * 의미 없는 사용자별 인덱스가 Redis에 남지 않게 한다.
         */
        assertThat(
            redisTemplate.hasKey(userFamiliesKey)
        ).isFalse();
    }

    @Test
    @DisplayName("이미 없거나 폐기된 Refresh Token Family는 다시 폐기하지 않는다")
    void revoke_returnsFalseWhenFamilyDoesNotExist() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        // when
        boolean revoked =
            refreshTokenStore.revoke(
                userId,
                familyId
            );

        // then
        assertThat(revoked).isFalse();

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(userId)
        ).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자가 소유한 Refresh Token Family는 폐기하지 않는다")
    void revoke_returnsFalseWhenUserDoesNotOwnFamily() {
        // given
        UUID ownerUserId =
            UUID.randomUUID();

        UUID otherUserId =
            UUID.randomUUID();

        UUID familyId =
            UUID.randomUUID();

        String tokenHash =
            "6".repeat(64);

        refreshTokenStore.save(
            ownerUserId,
            familyId,
            tokenHash,
            Duration.ofMinutes(30)
        );

        // when
        boolean revoked =
            refreshTokenStore.revoke(
                otherUserId,
                familyId
            );

        // then
        assertThat(revoked).isFalse();

        /*
         * 다른 사용자의 폐기 요청이 실패한 뒤에도
         * 원래 사용자의 Family 세션은 그대로 유지돼야 한다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    tokenHash
                )
        ).contains(ownerUserId);

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(ownerUserId)
        ).containsExactly(familyId);

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(otherUserId)
        ).isEmpty();
    }

    @Test
    @DisplayName("Rotation과 로그아웃이 동시에 실행돼도 Family 세션은 최종 폐기된다")
    void revoke_removesFamilyWhenCalledConcurrentlyWithRotation()
        throws Exception {

        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        String oldTokenHash =
            "7".repeat(64);

        String newTokenHash =
            "8".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        refreshTokenStore.save(
            userId,
            familyId,
            oldTokenHash,
            expiration
        );

        /*
         * 두 스레드가 같은 시점에 Rotation과 로그아웃을 요청하도록
         * 시작 신호용 CountDownLatch를 사용한다.
         */
        CountDownLatch readySignal =
            new CountDownLatch(2);

        CountDownLatch startSignal =
            new CountDownLatch(1);

        ExecutorService executorService =
            Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> rotationResult =
                executorService.submit(() -> {
                    readySignal.countDown();
                    startSignal.await();

                    return refreshTokenStore.rotate(
                        userId,
                        familyId,
                        oldTokenHash,
                        newTokenHash,
                        expiration
                    );
                });

            Future<Boolean> revokeResult =
                executorService.submit(() -> {
                    readySignal.countDown();
                    startSignal.await();

                    return refreshTokenStore.revoke(
                        userId,
                        familyId
                    );
                });

            // when
            assertThat(
                readySignal.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            startSignal.countDown();

            rotationResult.get(
                5,
                TimeUnit.SECONDS
            );

            boolean revoked =
                revokeResult.get(
                    5,
                    TimeUnit.SECONDS
                );

            // then
            /*
             * Family는 실행 순서와 관계없이 존재했던 상태이므로
             * 로그아웃 폐기 작업은 반드시 성공해야 한다.
             */
            assertThat(revoked).isTrue();

            /*
             * 어떤 실행 순서였더라도 기존 Token과 새 Token 모두
             * 로그아웃 완료 후 사용할 수 없어야 한다.
             */
            assertThat(
                refreshTokenStore
                    .findUserIdByFamilyAndTokenHash(
                        familyId,
                        oldTokenHash
                    )
            ).isEmpty();

            assertThat(
                refreshTokenStore
                    .findUserIdByFamilyAndTokenHash(
                        familyId,
                        newTokenHash
                    )
            ).isEmpty();

            assertThat(
                refreshTokenStore
                    .findFamilyIdsByUserId(userId)
            ).isEmpty();

            String familyKey =
                "auth:refresh-token:family:"
                    + familyId;

            String userFamiliesKey =
                "auth:refresh-token:user:"
                    + userId;

            assertThat(
                redisTemplate.hasKey(familyKey)
            ).isFalse();

            assertThat(
                redisTemplate.hasKey(userFamiliesKey)
            ).isFalse();
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("사용자의 모든 Refresh Token Family를 폐기한다")
    void revokeAllByUserId_success() {
        // given
        UUID targetUserId =
            UUID.randomUUID();

        UUID otherUserId =
            UUID.randomUUID();

        UUID firstFamilyId =
            UUID.randomUUID();

        UUID secondFamilyId =
            UUID.randomUUID();

        UUID otherFamilyId =
            UUID.randomUUID();

        String firstTokenHash =
            "9".repeat(64);

        String secondTokenHash =
            "a".repeat(64);

        String otherTokenHash =
            "b".repeat(64);

        Duration expiration =
            Duration.ofMinutes(30);

        /*
         * 폐기 대상 사용자에게 두 개의 로그인 세션을 저장
         * 예를 들어 PC 브라우저와 모바일 브라우저에서 각각
         * 로그인한 상태를 나타낸다.
         */
        refreshTokenStore.save(
            targetUserId,
            firstFamilyId,
            firstTokenHash,
            expiration
        );

        refreshTokenStore.save(
            targetUserId,
            secondFamilyId,
            secondTokenHash,
            expiration
        );

        /*
         * 다른 사용자의 세션도 함께 저장하여 전체 폐기가
         * 대상 사용자에게만 적용되는지 검증
         */
        refreshTokenStore.save(
            otherUserId,
            otherFamilyId,
            otherTokenHash,
            expiration
        );

        // when
        long revokedCount =
            refreshTokenStore.revokeAllByUserId(
                targetUserId
            );

        // then
        /*
         * 대상 사용자가 보유했던 두 개의 Family가 모두
         * 실제로 삭제됐으므로 2를 반환
         */
        assertThat(revokedCount)
            .isEqualTo(2L);

        /*
         * 폐기된 기존 Refresh Token 해시로는
         * 더 이상 사용자를 조회할 수 없어야 한다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    firstFamilyId,
                    firstTokenHash
                )
        ).isEmpty();

        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    secondFamilyId,
                    secondTokenHash
                )
        ).isEmpty();

        /*
         * 대상 사용자의 Family 인덱스도 함께 제거
         */
        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(
                    targetUserId
                )
        ).isEmpty();

        /*
         * 다른 사용자의 Refresh Token Family는
         * 전체 폐기의 영향을 받으면 안된다.
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    otherFamilyId,
                    otherTokenHash
                )
        ).contains(otherUserId);

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(
                    otherUserId
                )
        ).containsExactly(otherFamilyId);
    }

    @Test
    @DisplayName("활성 Refresh Token Family가 없는 사용자의 전체 폐기는 0을 반환한다")
    void revokeAllByUserId_returnsZeroWhenUserHasNoFamily() {
        // given
        UUID userId =
            UUID.randomUUID();

        // when
        long revokedCount =
            refreshTokenStore.revokeAllByUserId(
                userId
            );

        // then
        /*
         * 폐기할 세션이 없는 것은 오류가 아니라
         * 이미 안전한 상태이므로 0을 반환
         */
        assertThat(revokedCount)
            .isZero();

        assertThat(
            refreshTokenStore
                .findFamilyIdsByUserId(
                    userId
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("전체 세션 폐기의 사용자 UUID가 null이면 요청을 거부한다")
    void revokeAllByUserId_rejectsNullUserId() {
        // when & then
        assertThatThrownBy(() ->
            refreshTokenStore
                .revokeAllByUserId(
                    null
                )
        )
            .isInstanceOf(
                IllegalArgumentException.class
            )
            .hasMessage(
                "Refresh Token 사용자 UUID는 null일 수 없습니다."
            );

        /*
         * 유효하지 않은 요청으로 Redis 데이터가
         * 생성되거나 변경되지 않아야 한다.
         */
        assertThat(
            redisTemplate.keys(
                "auth:refresh-token:*"
            )
        ).isEmpty();
    }

    @Test
    @DisplayName("Refresh Token Family 폐기 인자가 올바르지 않으면 요청을 거부한다")
    void revoke_rejectsInvalidArguments() {
        // given
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();

        // when & then
        assertThatThrownBy(() ->
            refreshTokenStore.revoke(
                null,
                familyId
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
                "Refresh Token Family ID는 null일 수 없습니다."
            );

        /*
         * 잘못된 요청은 Redis에 어떤 데이터도 생성하지 않아야 한다.
         */
        assertThat(
            redisTemplate.keys(
                "auth:refresh-token:*"
            )
        ).isEmpty();
    }
}
