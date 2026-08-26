package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mopl.global.config.JpaConfig;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.storage.RefreshTokenStore;
import com.mopl.user.security.oauth.link.OAuthLinkIntent;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OAuth 계정 관리 서비스의 실제 데이터베이스 트랜잭션 동작을 검증
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    OAuthAccountManagementService.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
@Transactional(
    propagation = Propagation.NOT_SUPPORTED
)
class OAuthAccountManagementServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OAuthAccountManagementService managementService;

    @Autowired
    OAuthAccountRepository oauthAccountRepository;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    RefreshTokenStore refreshTokenStore;

    @BeforeEach
    void setUp() {
        oauthAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("세션 폐기에 실패하면 OAuth 계정 연결 삭제를 롤백한다")
    void unlinkAccount_rollsBack_whenRefreshTokenRevocationFails() {
        // given
        User user =
            User.builder()
                .email("local-user@example.com")
                .passwordHash("encoded-password")
                .name("로컬 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(user);

        UUID userId = user.getId();

        OAuthAccount oauthAccount =
            OAuthAccount.builder()
                .user(user)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-123")
                .build();

        oauthAccountRepository.saveAndFlush(
            oauthAccount
        );

        IllegalStateException redisException =
            new IllegalStateException(
                "Redis 세션 폐기 실패"
            );

        when(
            refreshTokenStore
                .revokeAllByUserId(userId)
        ).thenThrow(
            redisException
        );

        // when & then
        assertThatThrownBy(() ->
            managementService.unlinkAccount(
                userId,
                userId,
                OAuthProvider.GOOGLE
            )
        ).isSameAs(
            redisException
        );

        /*
         * 서비스 트랜잭션 종료 후 DB를 다시 조회
         * Redis 호출 전에 실행된 DELETE가 실제로 롤백됐는지 검증
         */
        assertThat(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    OAuthProvider.GOOGLE
                )
        ).isPresent();

        assertThat(
            oauthAccountRepository
                .countByUserId(userId)
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("동일한 OAuth 계정의 동시 연결은 한 사용자에게만 성공한다")
    void linkVerifiedAccount_concurrentRequestsAllowOnlyOneUser()
        throws Exception {
        // given
        User firstUser =
            User.builder()
                .email("first-user@example.com")
                .passwordHash("encoded-password")
                .name("첫 번째 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        User secondUser =
            User.builder()
                .email("second-user@example.com")
                .passwordHash("encoded-password")
                .name("두 번째 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        userRepository.saveAllAndFlush(
            java.util.List.of(
                firstUser,
                secondUser
            )
        );

        String providerUserId =
            "shared-google-sub";

        ExecutorService executorService =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready =
            new CountDownLatch(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {
            Future<ErrorCode> firstResultFuture =
                executorService.submit(() ->
                    linkAfterStart(
                        ready,
                        start,
                        firstUser.getId(),
                        OAuthProvider.GOOGLE,
                        providerUserId
                    )
                );

            Future<ErrorCode> secondResultFuture =
                executorService.submit(() ->
                    linkAfterStart(
                        ready,
                        start,
                        secondUser.getId(),
                        OAuthProvider.GOOGLE,
                        providerUserId
                    )
                );

            assertThat(
                ready.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            start.countDown();

            ErrorCode firstResult =
                firstResultFuture.get(
                    15,
                    TimeUnit.SECONDS
                );

            ErrorCode secondResult =
                secondResultFuture.get(
                    15,
                    TimeUnit.SECONDS
                );

            /*
             * 하나의 요청만 성공하고 다른 요청은
             * Provider 사용자 ID 고유 제약에 의해 거부되어야 한다.
             */
            assertThat(
                java.util.List.of(
                    firstResult == null,
                    secondResult == null
                )
            ).containsExactlyInAnyOrder(
                true,
                false
            );

            ErrorCode rejectedError =
                firstResult != null
                    ? firstResult
                    : secondResult;

            assertThat(rejectedError)
                .isEqualTo(
                    ErrorCode.OAUTH_ACCOUNT_CONFLICT
                );

            assertThat(
                oauthAccountRepository.count()
            ).isEqualTo(1);

            OAuthAccount linkedAccount =
                oauthAccountRepository
                    .findByProviderAndProviderUserId(
                        OAuthProvider.GOOGLE,
                        providerUserId
                    )
                    .orElseThrow();

            /*
             * 저장된 계정은 두 연결 요청 사용자 중 정확히 한 명에게만
             * 연결되어야 한다.
             */
            assertThat(
                linkedAccount
                    .getUser()
                    .getId()
            ).isIn(
                firstUser.getId(),
                secondUser.getId()
            );
        } finally {
            start.countDown();
            executorService.shutdownNow();

            assertThat(
                executorService.awaitTermination(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();
        }
    }

    @Test
    @DisplayName("OAuth 계정 동시 해제에서도 마지막 로그인 수단을 유지한다")
    void unlinkAccount_concurrentRequestsPreserveLastLoginMethod()
        throws Exception {
        // given
        User oauthOnlyUser =
            User.builder()
                .email("oauth-user@mopl.local")
                .passwordHash(null)
                .name("OAuth 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(
            oauthOnlyUser
        );

        UUID userId =
            oauthOnlyUser.getId();

        OAuthAccount googleAccount =
            OAuthAccount.builder()
                .user(oauthOnlyUser)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-123")
                .build();

        OAuthAccount kakaoAccount =
            OAuthAccount.builder()
                .user(oauthOnlyUser)
                .provider(OAuthProvider.KAKAO)
                .providerUserId("kakao-id-456")
                .build();

        oauthAccountRepository.saveAllAndFlush(
            java.util.List.of(
                googleAccount,
                kakaoAccount
            )
        );

        ExecutorService executorService =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready =
            new CountDownLatch(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {
            Future<ErrorCode> googleUnlink =
                executorService.submit(() ->
                    unlinkAfterStart(
                        ready,
                        start,
                        userId,
                        OAuthProvider.GOOGLE
                    )
                );

            Future<ErrorCode> kakaoUnlink =
                executorService.submit(() ->
                    unlinkAfterStart(
                        ready,
                        start,
                        userId,
                        OAuthProvider.KAKAO
                    )
                );

            /*
             * 두 작업 스레드가 모두 준비된 뒤 해제를 동시에 시작
             */
            assertThat(
                ready.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            start.countDown();

            ErrorCode googleResult =
                googleUnlink.get(
                    15,
                    TimeUnit.SECONDS
                );

            ErrorCode kakaoResult =
                kakaoUnlink.get(
                    15,
                    TimeUnit.SECONDS
                );

            /*
             * 두 요청 중 하나만 성공하고, 나머지 요청은
             * 마지막 로그인 수단 보호 정책에 의해 거부되어야 한다.
             */
            assertThat(
                java.util.List.of(
                    googleResult == null,
                    kakaoResult == null
                )
            ).containsExactlyInAnyOrder(
                true,
                false
            );

            ErrorCode rejectedError =
                googleResult != null
                    ? googleResult
                    : kakaoResult;

            assertThat(rejectedError)
                .isEqualTo(
                    ErrorCode.OAUTH_LAST_LOGIN_METHOD
                );

            /*
             * 실제 PostgreSQL에도 OAuth 계정 하나가 남아 있어야 한다.
             */
            assertThat(
                oauthAccountRepository
                    .countByUserId(userId)
            ).isEqualTo(1);

            assertThat(
                oauthAccountRepository
                    .findAllByUserId(userId)
            ).hasSize(1);

            /*
             * 실제 삭제에 성공한 요청에서만 세션 폐기가 실행
             */
            verify(
                refreshTokenStore,
                times(1)
            ).revokeAllByUserId(userId);
        } finally {
            start.countDown();
            executorService.shutdownNow();

            assertThat(
                executorService.awaitTermination(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();
        }
    }

    /**
     * OAuth 연결 작업을 동시에 시작하고 처리 결과를 반환
     *
     * @return 성공하면 null, 연결 충돌이면 해당 ErrorCode
     */
    private ErrorCode linkAfterStart(
        CountDownLatch ready,
        CountDownLatch start,
        UUID userId,
        OAuthProvider provider,
        String providerUserId
    ) throws InterruptedException {
        ready.countDown();

        if (!start.await(
            5,
            TimeUnit.SECONDS
        )) {
            throw new IllegalStateException(
                "동시 OAuth 연결 시작 신호를 기다리지 못했습니다."
            );
        }

        OAuthLinkIntent linkIntent =
            new OAuthLinkIntent(
                userId,
                provider,
                Instant.parse(
                    "2099-01-01T00:00:00Z"
                )
            );

        try {
            managementService
                .linkVerifiedAccount(
                    linkIntent,
                    provider,
                    providerUserId
                );

            return null;
        } catch (BusinessException exception) {
            return exception.getErrorCode();
        }
    }

    /**
     * 두 연결 해제 작업을 같은 시점에 시작하고 처리 결과를 반환
     *
     * @return 성공하면 null, 정책에 의해 거부되면 해당 ErrorCode
     */
    private ErrorCode unlinkAfterStart(
        CountDownLatch ready,
        CountDownLatch start,
        UUID userId,
        OAuthProvider provider
    ) throws InterruptedException {
        ready.countDown();

        if (!start.await(
            5,
            TimeUnit.SECONDS
        )) {
            throw new IllegalStateException(
                "동시 연결 해제 시작 신호를 기다리지 못했습니다."
            );
        }

        try {
            managementService.unlinkAccount(
                userId,
                userId,
                provider
            );

            return null;
        } catch (BusinessException exception) {
            return exception.getErrorCode();
        }
    }
}
