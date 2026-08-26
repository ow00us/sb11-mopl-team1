package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.mopl.user.config.OAuthLocalCredentialProperties;
import com.mopl.user.dto.LocalCredentialEmailVerificationRequest;
import com.mopl.user.dto.LocalCredentialRegistrationRequest;
import com.mopl.user.entity.OAuthAccount;
import com.mopl.user.entity.OAuthProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.mail.EmailVerificationCodeSender;
import com.mopl.user.repository.OAuthAccountRepository;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.EmailVerificationCodeHasher;
import com.mopl.user.storage.EmailVerificationConsumeResult;
import com.mopl.user.storage.EmailVerificationStore;
import com.mopl.user.storage.RefreshTokenStore;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * OAuth 전용 사용자의 이메일 인증 및 로컬 로그인 수단 등록을
 * 실제 PostgreSQL과 Redis로 검증하는 통합 테스트
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OAuthLocalCredentialIntegrationTest {

    private static final String RAW_PASSWORD =
        "Password1!";

    private static final String REFRESH_TOKEN_HASH =
        "a".repeat(64);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(
            "postgres:16"
        );

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
        new GenericContainer<>(
            DockerImageName.parse("redis:7")
        )
            .withExposedPorts(6379);

    @Autowired
    OAuthLocalCredentialService localCredentialService;

    @Autowired
    OAuthLocalCredentialRegistrationService registrationService;

    @Autowired
    OAuthLocalCredentialProperties properties;

    @Autowired
    EmailVerificationCodeHasher verificationCodeHasher;

    @Autowired
    EmailVerificationStore verificationStore;

    /**
     * 실제 Redis 구현을 사용하되 롤백 테스트에서는
     * 세션 폐기 실패를 발생시킬 수 있도록 Spy로 감싼다.
     */
    @MockitoSpyBean
    RefreshTokenStore refreshTokenStore;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OAuthAccountRepository oauthAccountRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    MoplUserDetailsService userDetailsService;

    @Autowired
    StringRedisTemplate redisTemplate;

    /**
     * 실제 SMTP 서버 대신 발송된 인증 코드를 캡처
     */
    @MockitoBean
    EmailVerificationCodeSender verificationCodeSender;

    @BeforeEach
    void cleanUp() {
        oauthAccountRepository.deleteAll();
        userRepository.deleteAll();

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

    @ParameterizedTest
    @CsvSource({
        "KAKAO,kakao-user@oauth.invalid,user@example.com",
        "GOOGLE,google-user@example.com,google-user@example.com"
    })
    @DisplayName("OAuth 전용 사용자는 인증한 이메일과 비밀번호로 로컬 로그인을 등록한다")
    void registerLocalCredential_endToEnd(
        OAuthProvider provider,
        String initialEmail,
        String requestedEmail
    ) {
        // given
        User user =
            saveOAuthUser(
                provider,
                initialEmail
            );

        UUID userId = user.getId();
        UUID familyId = UUID.randomUUID();

        /*
         * 등록 성공 후 기존 로그인 세션이 폐기되는지 확인하기 위해
         * 실제 Redis에 Refresh Token Family를 저장
         */
        refreshTokenStore.save(
            userId,
            familyId,
            REFRESH_TOKEN_HASH,
            Duration.ofMinutes(30)
        );

        localCredentialService
            .sendVerificationCode(
                userId,
                userId,
                new LocalCredentialEmailVerificationRequest(
                    requestedEmail
                )
            );

        ArgumentCaptor<String> codeCaptor =
            ArgumentCaptor.forClass(
                String.class
            );

        verify(verificationCodeSender)
            .send(
                eq(requestedEmail),
                codeCaptor.capture(),
                eq(
                    properties
                        .getVerificationExpiration()
                )
            );

        String verificationCode =
            codeCaptor.getValue();

        // when
        localCredentialService
            .registerLocalCredential(
                userId,
                userId,
                new LocalCredentialRegistrationRequest(
                    requestedEmail,
                    verificationCode,
                    RAW_PASSWORD
                )
            );

        // then
        User updatedUser =
            userRepository
                .findById(userId)
                .orElseThrow();

        assertThat(updatedUser.getEmail())
            .isEqualTo(requestedEmail);

        assertThat(
            passwordEncoder.matches(
                RAW_PASSWORD,
                updatedUser.getPasswordHash()
            )
        ).isTrue();

        /*
         * 실제 UserDetailsService에서도 새 이메일과 비밀번호 해시를
         * 로컬 로그인 정보로 조회할 수 있어야 한다.
         */
        UserDetails userDetails =
            userDetailsService
                .loadUserByUsername(
                    requestedEmail
                );

        assertThat(userDetails.getUsername())
            .isEqualTo(requestedEmail);

        assertThat(
            passwordEncoder.matches(
                RAW_PASSWORD,
                userDetails.getPassword()
            )
        ).isTrue();

        /*
         * OAuth 연결은 유지되어 이메일 로그인과 소셜 로그인을
         * 모두 사용할 수 있어야 한다.
         */
        assertThat(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    provider
                )
        ).isPresent();

        /*
         * 보안 상태 변경 후 기존 Refresh Token Family가 폐기됐는지
         * 실제 Redis 저장소를 통해 확인
         */
        assertThat(
            refreshTokenStore
                .findUserIdByFamilyAndTokenHash(
                    familyId,
                    REFRESH_TOKEN_HASH
                )
        ).isEmpty();

        /*
         * 성공한 인증 코드는 Redis에서 소비되어 다시 사용할 수 없어야 한다.
         */
        String consumedCodeHash =
            verificationCodeHasher.hash(
                userId,
                requestedEmail,
                verificationCode
            );

        assertThat(
            verificationStore.consume(
                userId,
                requestedEmail,
                consumedCodeHash,
                properties.getMaxAttempts()
            )
        ).isEqualTo(
            EmailVerificationConsumeResult.NOT_FOUND
        );
    }

    @Test
    @DisplayName("Refresh Token 세션 폐기에 실패하면 이메일과 비밀번호 등록을 롤백한다")
    void registerLocalCredential_rollsBack_whenRevocationFails() {
        // given
        String initialEmail =
            "kakao-user@oauth.invalid";

        String requestedEmail =
            "rollback-user@example.com";

        User user =
            saveOAuthUser(
                OAuthProvider.KAKAO,
                initialEmail
            );

        UUID userId = user.getId();

        String encodedPassword =
            passwordEncoder.encode(
                RAW_PASSWORD
            );

        IllegalStateException redisException =
            new IllegalStateException(
                "Redis 세션 폐기 실패"
            );

        doThrow(redisException)
            .when(refreshTokenStore)
            .revokeAllByUserId(
                userId
            );

        // when & then
        assertThatThrownBy(() ->
            registrationService.register(
                userId,
                requestedEmail,
                encodedPassword
            )
        ).isSameAs(
            redisException
        );

        /*
         * 트랜잭션 종료 후 PostgreSQL에서 다시 조회해
         * 실제 롤백 결과를 확인
         */
        User unchangedUser =
            userRepository
                .findById(userId)
                .orElseThrow();

        assertThat(unchangedUser.getEmail())
            .isEqualTo(initialEmail);

        assertThat(unchangedUser.getPasswordHash())
            .isNull();

        assertThat(
            oauthAccountRepository
                .findByUserIdAndProvider(
                    userId,
                    OAuthProvider.KAKAO
                )
        ).isPresent();
    }

    private User saveOAuthUser(
        OAuthProvider provider,
        String email
    ) {
        User user =
            User.builder()
                .email(email)
                .passwordHash(null)
                .name("OAuth 사용자")
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        User savedUser =
            userRepository.saveAndFlush(
                user
            );

        OAuthAccount oauthAccount =
            OAuthAccount.builder()
                .user(savedUser)
                .provider(provider)
                .providerUserId(
                    provider
                        .name()
                        .toLowerCase()
                        + "-"
                        + UUID.randomUUID()
                )
                .build();

        oauthAccountRepository
            .saveAndFlush(
                oauthAccount
            );

        return savedUser;
    }
}
