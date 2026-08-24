package com.mopl.user.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * 이메일 인증 상태 저장소의 실제 Redis 연동을 검증
 */
@DataRedisTest
@ActiveProfiles("test")
@Import(RedisEmailVerificationStore.class)
@Testcontainers
class RedisEmailVerificationStoreTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final String EMAIL =
        "user@example.com";

    private static final String CODE_HASH =
        "a".repeat(64);

    private static final String DIFFERENT_CODE_HASH =
        "b".repeat(64);

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
    EmailVerificationStore verificationStore;

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
    @DisplayName("인증 코드 해시와 이메일을 TTL이 있는 상태로 저장한다")
    void issue_storesVerificationStateWithTtl() {
        // when
        EmailVerificationIssueResult result =
            issue(
                CODE_HASH,
                Duration.ofMinutes(10),
                Duration.ofMinutes(1)
            );

        // then
        assertThat(result)
            .isEqualTo(
                EmailVerificationIssueResult.ISSUED
            );

        String verificationKey =
            verificationKey();

        assertThat(
            redisTemplate.opsForHash()
                .get(
                    verificationKey,
                    "email"
                )
        ).isEqualTo(EMAIL);

        assertThat(
            redisTemplate.opsForHash()
                .get(
                    verificationKey,
                    "codeHash"
                )
        ).isEqualTo(CODE_HASH);

        assertThat(
            redisTemplate.opsForHash()
                .get(
                    verificationKey,
                    "attempts"
                )
        ).isEqualTo("0");

        Long ttl =
            redisTemplate.getExpire(
                verificationKey,
                TimeUnit.MILLISECONDS
            );

        assertThat(ttl)
            .isPositive()
            .isLessThanOrEqualTo(
                Duration.ofMinutes(10)
                    .toMillis()
            );
    }

    @Test
    @DisplayName("재전송 제한 중에는 기존 인증 상태를 덮어쓰지 않는다")
    void issue_doesNotOverwriteDuringCooldown() {
        // given
        issue(
            CODE_HASH,
            Duration.ofMinutes(10),
            Duration.ofMinutes(1)
        );

        // when
        EmailVerificationIssueResult result =
            issue(
                DIFFERENT_CODE_HASH,
                Duration.ofMinutes(10),
                Duration.ofMinutes(1)
            );

        // then
        assertThat(result)
            .isEqualTo(
                EmailVerificationIssueResult.COOLDOWN_ACTIVE
            );

        assertThat(
            redisTemplate.opsForHash()
                .get(
                    verificationKey(),
                    "codeHash"
                )
        ).isEqualTo(CODE_HASH);
    }

    @Test
    @DisplayName("재전송 제한이 만료되면 새 인증 상태를 저장할 수 있다")
    void issue_allowsNewCodeAfterCooldown() {
        // given
        issue(
            CODE_HASH,
            Duration.ofSeconds(2),
            Duration.ofMillis(100)
        );

        // when & then
        await()
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                EmailVerificationIssueResult result =
                    issue(
                        DIFFERENT_CODE_HASH,
                        Duration.ofSeconds(2),
                        Duration.ofMillis(100)
                    );

                assertThat(result)
                    .isEqualTo(
                        EmailVerificationIssueResult.ISSUED
                    );
            });

        assertThat(
            redisTemplate.opsForHash()
                .get(
                    verificationKey(),
                    "codeHash"
                )
        ).isEqualTo(DIFFERENT_CODE_HASH);
    }

    @Test
    @DisplayName("일치하는 인증 코드는 한 번만 소비할 수 있다")
    void consume_verifiesOnlyOnce() {
        // given
        issue(
            CODE_HASH,
            Duration.ofMinutes(10),
            Duration.ofMinutes(1)
        );

        // when
        EmailVerificationConsumeResult first =
            verificationStore.consume(
                USER_ID,
                EMAIL,
                CODE_HASH,
                5
            );

        EmailVerificationConsumeResult second =
            verificationStore.consume(
                USER_ID,
                EMAIL,
                CODE_HASH,
                5
            );

        // then
        assertThat(first)
            .isEqualTo(
                EmailVerificationConsumeResult.VERIFIED
            );

        assertThat(second)
            .isEqualTo(
                EmailVerificationConsumeResult.NOT_FOUND
            );
    }

    @Test
    @DisplayName("잘못된 인증 코드는 실패 횟수를 증가시킨다")
    void consume_incrementsAttemptsForInvalidCode() {
        // given
        issue(
            CODE_HASH,
            Duration.ofMinutes(10),
            Duration.ofMinutes(1)
        );

        // when
        EmailVerificationConsumeResult result =
            verificationStore.consume(
                USER_ID,
                EMAIL,
                DIFFERENT_CODE_HASH,
                5
            );

        // then
        assertThat(result)
            .isEqualTo(
                EmailVerificationConsumeResult.INVALID
            );

        assertThat(
            redisTemplate.opsForHash()
                .get(
                    verificationKey(),
                    "attempts"
                )
        ).isEqualTo("1");
    }

    @Test
    @DisplayName("최대 실패 횟수에 도달하면 인증 상태를 폐기한다")
    void consume_deletesStateWhenAttemptsAreExhausted() {
        // given
        issue(
            CODE_HASH,
            Duration.ofMinutes(10),
            Duration.ofMinutes(1)
        );

        EmailVerificationConsumeResult first =
            verificationStore.consume(
                USER_ID,
                EMAIL,
                DIFFERENT_CODE_HASH,
                2
            );

        // when
        EmailVerificationConsumeResult second =
            verificationStore.consume(
                USER_ID,
                EMAIL,
                DIFFERENT_CODE_HASH,
                2
            );

        // then
        assertThat(first)
            .isEqualTo(
                EmailVerificationConsumeResult.INVALID
            );

        assertThat(second)
            .isEqualTo(
                EmailVerificationConsumeResult.ATTEMPTS_EXHAUSTED
            );

        assertThat(
            redisTemplate.hasKey(
                verificationKey()
            )
        ).isFalse();
    }

    @Test
    @DisplayName("다른 이메일을 제출해도 인증 실패 횟수를 증가시킨다")
    void consume_rejectsDifferentEmail() {
        // given
        issue(
            CODE_HASH,
            Duration.ofMinutes(10),
            Duration.ofMinutes(1)
        );

        // when
        EmailVerificationConsumeResult result =
            verificationStore.consume(
                USER_ID,
                "different@example.com",
                CODE_HASH,
                5
            );

        // then
        assertThat(result)
            .isEqualTo(
                EmailVerificationConsumeResult.INVALID
            );

        assertThat(
            redisTemplate.opsForHash()
                .get(
                    verificationKey(),
                    "attempts"
                )
        ).isEqualTo("1");
    }

    @Test
    @DisplayName("TTL이 지난 인증 상태는 사용할 수 없다")
    void consume_returnsNotFoundAfterExpiration() {
        // given
        issue(
            CODE_HASH,
            Duration.ofMillis(150),
            Duration.ofMillis(50)
        );

        // when & then
        await()
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                EmailVerificationConsumeResult result =
                    verificationStore.consume(
                        USER_ID,
                        EMAIL,
                        CODE_HASH,
                        5
                    );

                assertThat(result)
                    .isEqualTo(
                        EmailVerificationConsumeResult.NOT_FOUND
                    );
            });
    }

    @Test
    @DisplayName("동시 인증 요청에서도 하나의 요청만 성공한다")
    void consume_allowsOnlyOneConcurrentSuccess()
        throws Exception {

        // given
        issue(
            CODE_HASH,
            Duration.ofMinutes(10),
            Duration.ofMinutes(1)
        );

        ExecutorService executorService =
            Executors.newFixedThreadPool(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {
            Future<EmailVerificationConsumeResult>
                firstFuture =
                executorService.submit(() -> {
                    start.await();

                    return verificationStore.consume(
                        USER_ID,
                        EMAIL,
                        CODE_HASH,
                        5
                    );
                });

            Future<EmailVerificationConsumeResult>
                secondFuture =
                executorService.submit(() -> {
                    start.await();

                    return verificationStore.consume(
                        USER_ID,
                        EMAIL,
                        CODE_HASH,
                        5
                    );
                });

            // when
            start.countDown();

            List<EmailVerificationConsumeResult>
                results =
                List.of(
                    firstFuture.get(
                        3,
                        TimeUnit.SECONDS
                    ),
                    secondFuture.get(
                        3,
                        TimeUnit.SECONDS
                    )
                );

            // then
            assertThat(results)
                .containsExactlyInAnyOrder(
                    EmailVerificationConsumeResult.VERIFIED,
                    EmailVerificationConsumeResult.NOT_FOUND
                );
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("사용자의 인증 상태와 재전송 제한을 함께 삭제한다")
    void deleteByUserId_removesVerificationAndCooldown() {
        // given
        issue(
            CODE_HASH,
            Duration.ofMinutes(10),
            Duration.ofMinutes(1)
        );

        // when
        verificationStore.deleteByUserId(
            USER_ID
        );

        // then
        assertThat(
            redisTemplate.hasKey(
                verificationKey()
            )
        ).isFalse();

        assertThat(
            redisTemplate.hasKey(
                cooldownKey()
            )
        ).isFalse();

        assertThat(
            issue(
                DIFFERENT_CODE_HASH,
                Duration.ofMinutes(10),
                Duration.ofMinutes(1)
            )
        ).isEqualTo(
            EmailVerificationIssueResult.ISSUED
        );
    }

    private EmailVerificationIssueResult issue(
        String codeHash,
        Duration expiration,
        Duration cooldown
    ) {
        return verificationStore.issue(
            USER_ID,
            EMAIL,
            codeHash,
            expiration,
            cooldown
        );
    }

    private String verificationKey() {
        return "auth:local-credential:verification:{"
            + USER_ID
            + "}";
    }

    private String cooldownKey() {
        return "auth:local-credential:cooldown:{"
            + USER_ID
            + "}";
    }
}
