package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OAuth 사용자 조회·생성 서비스가 실제 PostgreSQL에서
 * 사용자와 OAuth 연결 정보를 일관되게 저장하는지 검증
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    OAuthUserCreationService.class,
    OAuthUserProvisioningService.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
@Transactional(
    propagation = Propagation.NOT_SUPPORTED
)
class OAuthUserProvisioningServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OAuthUserProvisioningService provisioningService;

    @Autowired
    OAuthAccountRepository oauthAccountRepository;

    @Autowired
    UserRepository userRepository;

    @BeforeEach
    void setUp() {
        /*
         * OAuth 계정이 users를 참조하므로
         * 외래 키의 반대 순서로 데이터를 제거
         */
        oauthAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("최초 Google 로그인은 OAuth 전용 사용자와 연결 정보를 함께 생성한다")
    void resolveOrCreate_createGoogleUserAndOAuthAccount() {
        // when
        User result =
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                " User@Example.Com ",
                " Google 사용자 ",
                " https://example.com/profile.png "
            );

        // then
        assertThat(result.getId())
            .isNotNull();
        assertThat(result.getEmail())
            .isEqualTo("user@example.com");
        assertThat(result.getPasswordHash())
            .isNull();
        assertThat(result.getName())
            .isEqualTo("Google 사용자");
        assertThat(result.getProfileImageUrl())
            .isEqualTo(
                "https://example.com/profile.png"
            );
        assertThat(result.getRole())
            .isEqualTo(UserRole.USER);
        assertThat(result.isLocked())
            .isFalse();

        assertThat(userRepository.count())
            .isEqualTo(1);
        assertThat(oauthAccountRepository.count())
            .isEqualTo(1);

        OAuthAccount oauthAccount =
            oauthAccountRepository
                .findByProviderAndProviderUserId(
                    OAuthProvider.GOOGLE,
                    "google-sub-123"
                )
                .orElseThrow();

        assertThat(oauthAccount.getUser().getId())
            .isEqualTo(result.getId());
        assertThat(oauthAccount.getProvider())
            .isEqualTo(OAuthProvider.GOOGLE);
        assertThat(oauthAccount.getProviderUserId())
            .isEqualTo("google-sub-123");
    }

    @Test
    @DisplayName("이미 연결된 Google 계정은 사용자를 중복 생성하지 않는다")
    void resolveOrCreate_returnExistingLinkedUser() {
        // given
        User first =
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                "user@example.com",
                "Google 사용자",
                null
            );

        /*
         * 연결된 계정의 식별 기준은 Google sub이므로
         * 이후 Google 프로필 정보가 없거나 변경되어도
         * 기존 MOPL 사용자를 반환해야 한다.
         */
        User second =
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                null,
                null,
                null
            );

        // then
        assertThat(second.getId())
            .isEqualTo(first.getId());

        assertThat(userRepository.count())
            .isEqualTo(1);
        assertThat(oauthAccountRepository.count())
            .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 Google 계정의 동시 최초 로그인은 동일한 사용자로 수렴한다")
    void resolveOrCreate_concurrentGoogleLoginConvergesToSameUser()
        throws Exception {
        // given
        ExecutorService executorService =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready =
            new CountDownLatch(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {
            Future<User> firstLogin =
                executorService.submit(() -> {
                    ready.countDown();

                    if (!start.await(
                        5,
                        TimeUnit.SECONDS
                    )) {
                        throw new IllegalStateException(
                            "동시 로그인 시작 신호를 기다리지 못했습니다."
                        );
                    }

                    return provisioningService.resolveOrCreate(
                        OAuthProvider.GOOGLE,
                        "concurrent-google-sub",
                        "concurrent@example.com",
                        "동시 로그인 사용자",
                        null
                    );
                });

            Future<User> secondLogin =
                executorService.submit(() -> {
                    ready.countDown();

                    if (!start.await(
                        5,
                        TimeUnit.SECONDS
                    )) {
                        throw new IllegalStateException(
                            "동시 로그인 시작 신호를 기다리지 못했습니다."
                        );
                    }

                    return provisioningService.resolveOrCreate(
                        OAuthProvider.GOOGLE,
                        "concurrent-google-sub",
                        "concurrent@example.com",
                        "동시 로그인 사용자",
                        null
                    );
                });

            /*
             * 두 작업 스레드가 모두 준비된 이후 동시에
             * OAuth 사용자 조회·생성을 시작
             */
            assertThat(
                ready.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            start.countDown();

            // when
            User firstResult =
                firstLogin.get(
                    15,
                    TimeUnit.SECONDS
                );

            User secondResult =
                secondLogin.get(
                    15,
                    TimeUnit.SECONDS
                );

            // then
            assertThat(firstResult.getId())
                .isNotNull();

            assertThat(secondResult.getId())
                .isEqualTo(firstResult.getId());

            assertThat(userRepository.count())
                .isEqualTo(1);

            assertThat(oauthAccountRepository.count())
                .isEqualTo(1);

            OAuthAccount savedAccount =
                oauthAccountRepository
                    .findByProviderAndProviderUserId(
                        OAuthProvider.GOOGLE,
                        "concurrent-google-sub"
                    )
                    .orElseThrow();

            assertThat(savedAccount.getUser().getId())
                .isEqualTo(firstResult.getId());
        } finally {
            /*
             * 테스트 성공 여부와 관계없이 작업 스레드를 정리
             */
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
    @DisplayName("기존 로컬 사용자의 이메일과 같아도 OAuth 계정을 자동 연결하지 않는다")
    void resolveOrCreate_fail_whenLocalEmailExists() {
        // given
        User localUser =
            User.builder()
                .email("user@example.com")
                .passwordHash("encoded-password")
                .name("로컬 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        userRepository.saveAndFlush(localUser);

        // when & then
        assertThatThrownBy(() ->
            provisioningService.resolveOrCreate(
                OAuthProvider.GOOGLE,
                "google-sub-123",
                " User@Example.Com ",
                "Google 사용자",
                null
            )
        )
            .isInstanceOf(
                OAuth2AuthenticationException.class
            )
            .satisfies(exception -> {
                OAuth2AuthenticationException oauthException =
                    (OAuth2AuthenticationException) exception;

                assertThat(
                    oauthException
                        .getError()
                        .getErrorCode()
                ).isEqualTo(
                    OAuthUserProvisioningService
                        .ACCOUNT_LINK_REQUIRED
                );
            });

        /*
         * 기존 사용자만 남고 OAuth 연결 정보나
         * 추가 사용자는 생성되지 않아야 한다.
         */
        assertThat(userRepository.count())
            .isEqualTo(1);
        assertThat(oauthAccountRepository.count())
            .isZero();
    }
}
