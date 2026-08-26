package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.config.OAuthLocalCredentialProperties;
import com.mopl.user.dto.LocalCredentialEmailVerificationRequest;
import com.mopl.user.dto.LocalCredentialRegistrationRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.mail.EmailVerificationCodeSender;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.EmailVerificationCodeGenerator;
import com.mopl.user.security.EmailVerificationCodeHasher;
import com.mopl.user.storage.EmailVerificationConsumeResult;
import com.mopl.user.storage.EmailVerificationIssueResult;
import com.mopl.user.storage.EmailVerificationStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * OAuth 로컬 로그인 이메일 인증 코드 발급 서비스 검증
 */
@ExtendWith(MockitoExtension.class)
class OAuthLocalCredentialServiceTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final String RAW_CODE =
        "123456";

    private static final String CODE_HASH =
        "a".repeat(64);

    private static final Duration EXPIRATION =
        Duration.ofMinutes(10);

    private static final Duration COOLDOWN =
        Duration.ofMinutes(1);

    @Mock
    UserRepository userRepository;

    @Mock
    EmailVerificationCodeGenerator verificationCodeGenerator;

    @Mock
    EmailVerificationCodeHasher verificationCodeHasher;

    @Mock
    EmailVerificationStore verificationStore;

    @Mock
    EmailVerificationCodeSender verificationCodeSender;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    OAuthLocalCredentialRegistrationService registrationService;

    OAuthLocalCredentialProperties properties;
    OAuthLocalCredentialService service;

    @BeforeEach
    void setUp() {
        properties =
            new OAuthLocalCredentialProperties();

        properties.setVerificationExpiration(
            EXPIRATION
        );

        properties.setResendCooldown(
            COOLDOWN
        );

        properties.setMaxAttempts(5);

        properties.setVerificationSecret(
            "test-oauth-local-verification-secret-1234"
        );

        service =
            new OAuthLocalCredentialService(
                userRepository,
                verificationCodeGenerator,
                verificationCodeHasher,
                verificationStore,
                verificationCodeSender,
                passwordEncoder,
                registrationService,
                properties
            );
    }

    @Test
    @DisplayName("OAuth 전용 사용자에게 정규화된 이메일로 인증 코드를 발송한다")
    void sendVerificationCode_success() {
        // given
        User user =
            oauthOnlyUser(false);

        when(
            userRepository.findById(USER_ID)
        ).thenReturn(
            Optional.of(user)
        );

        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        ).thenReturn(false);

        when(
            verificationCodeGenerator.generate()
        ).thenReturn(RAW_CODE);

        when(
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                RAW_CODE
            )
        ).thenReturn(CODE_HASH);

        when(
            verificationStore.issue(
                USER_ID,
                "user@example.com",
                CODE_HASH,
                EXPIRATION,
                COOLDOWN
            )
        ).thenReturn(
            EmailVerificationIssueResult.ISSUED
        );

        LocalCredentialEmailVerificationRequest request =
            new LocalCredentialEmailVerificationRequest(
                " User@Example.com "
            );

        // when
        service.sendVerificationCode(
            USER_ID,
            USER_ID,
            request
        );

        // then
        verify(verificationCodeSender)
            .send(
                "user@example.com",
                RAW_CODE,
                EXPIRATION
            );
    }

    @Test
    @DisplayName("인증 사용자가 없으면 401 오류를 반환한다")
    void sendVerificationCode_rejectsUnauthenticatedRequest() {
        // given
        LocalCredentialEmailVerificationRequest request =
            request();

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.sendVerificationCode(
                        null,
                        USER_ID,
                        request
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.UNAUTHORIZED
            );

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("다른 사용자의 로컬 로그인 수단 추가 요청은 403을 반환한다")
    void sendVerificationCode_rejectsDifferentUser() {
        // given
        LocalCredentialEmailVerificationRequest request =
            request();

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.sendVerificationCode(
                        UUID.randomUUID(),
                        USER_ID,
                        request
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.FORBIDDEN
            );

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("잠긴 사용자는 인증 코드를 요청할 수 없다")
    void sendVerificationCode_rejectsLockedUser() {
        // given
        when(
            userRepository.findById(USER_ID)
        ).thenReturn(
            Optional.of(
                oauthOnlyUser(true)
            )
        );

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.sendVerificationCode(
                        USER_ID,
                        USER_ID,
                        request()
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.FORBIDDEN
            );

        verifyNoInteractions(
            verificationCodeGenerator,
            verificationCodeSender
        );
    }

    @Test
    @DisplayName("이미 로컬 비밀번호가 있는 사용자는 인증 코드를 요청할 수 없다")
    void sendVerificationCode_rejectsExistingLocalCredential() {
        // given
        when(
            userRepository.findById(USER_ID)
        ).thenReturn(
            Optional.of(
                localUser()
            )
        );

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.sendVerificationCode(
                        USER_ID,
                        USER_ID,
                        request()
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.LOCAL_CREDENTIAL_ALREADY_EXISTS
            );

        verifyNoInteractions(
            verificationCodeGenerator,
            verificationCodeSender
        );
    }

    @Test
    @DisplayName("OAuth 내부 식별 이메일은 실제 로그인 이메일로 요청할 수 없다")
    void sendVerificationCode_rejectsInternalOAuthEmail() {
        // given
        LocalCredentialEmailVerificationRequest request =
            new LocalCredentialEmailVerificationRequest(
                "google-user@oauth.invalid"
            );

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.sendVerificationCode(
                        USER_ID,
                        USER_ID,
                        request
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.INVALID_INPUT
            );

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("이미 사용 중인 이메일이면 409 오류를 반환한다")
    void sendVerificationCode_rejectsDuplicatedEmail() {
        // given
        when(
            userRepository.findById(USER_ID)
        ).thenReturn(
            Optional.of(
                oauthOnlyUser(false)
            )
        );

        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        ).thenReturn(true);

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.sendVerificationCode(
                        USER_ID,
                        USER_ID,
                        request()
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.EMAIL_DUPLICATE
            );

        verifyNoInteractions(
            verificationCodeGenerator,
            verificationCodeSender
        );
    }

    @Test
    @DisplayName("현재 OAuth 사용자가 이미 가진 실제 이메일은 중복으로 판단하지 않는다")
    void sendVerificationCode_allowsCurrentUserEmail() {
        // given
        User user =
            oauthOnlyUserWithActualEmail(false);

        when(
            userRepository.findById(USER_ID)
        ).thenReturn(
            Optional.of(user)
        );

        when(
            verificationCodeGenerator.generate()
        ).thenReturn(RAW_CODE);

        when(
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                RAW_CODE
            )
        ).thenReturn(CODE_HASH);

        when(
            verificationStore.issue(
                USER_ID,
                "user@example.com",
                CODE_HASH,
                EXPIRATION,
                COOLDOWN
            )
        ).thenReturn(
            EmailVerificationIssueResult.ISSUED
        );

        // when
        service.sendVerificationCode(
            USER_ID,
            USER_ID,
            request()
        );

        // then
        verify(
            userRepository,
            never()
        ).existsByEmail(
            "user@example.com"
        );

        verify(verificationCodeSender)
            .send(
                "user@example.com",
                RAW_CODE,
                EXPIRATION
            );
    }

    @Test
    @DisplayName("재전송 제한 중이면 메일을 발송하지 않고 429를 반환한다")
    void sendVerificationCode_rejectsActiveCooldown() {
        // given
        prepareIssueRequest();

        when(
            verificationStore.issue(
                USER_ID,
                "user@example.com",
                CODE_HASH,
                EXPIRATION,
                COOLDOWN
            )
        ).thenReturn(
            EmailVerificationIssueResult.COOLDOWN_ACTIVE
        );

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.sendVerificationCode(
                        USER_ID,
                        USER_ID,
                        request()
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.EMAIL_VERIFICATION_COOLDOWN
            );

        verify(
            verificationCodeSender,
            never()
        ).send(
            "user@example.com",
            RAW_CODE,
            EXPIRATION
        );
    }

    @Test
    @DisplayName("메일 발송이 실패하면 이번 요청의 인증 상태를 조건부 삭제한다")
    void sendVerificationCode_compensatesWhenMailFails() {
        // given
        prepareIssueRequest();

        when(
            verificationStore.issue(
                USER_ID,
                "user@example.com",
                CODE_HASH,
                EXPIRATION,
                COOLDOWN
            )
        ).thenReturn(
            EmailVerificationIssueResult.ISSUED
        );

        MailSendException mailException =
            new MailSendException(
                "SMTP 서버 연결 실패"
            );

        org.mockito.Mockito.doThrow(
                mailException
            )
            .when(verificationCodeSender)
            .send(
                "user@example.com",
                RAW_CODE,
                EXPIRATION
            );

        // when
        MailSendException thrown =
            catchThrowableOfType(
                () ->
                    service.sendVerificationCode(
                        USER_ID,
                        USER_ID,
                        request()
                    ),
                MailSendException.class
            );

        // then
        assertThat(thrown)
            .isSameAs(mailException);

        verify(verificationStore)
            .deleteIfCodeHashMatches(
                USER_ID,
                CODE_HASH
            );
    }

    @Test
    @DisplayName("검증된 인증 코드로 실제 이메일과 비밀번호를 등록한다")
    void registerLocalCredential_success() {
        // given
        prepareRegistrationRequest();

        when(
            verificationStore.consume(
                USER_ID,
                "user@example.com",
                CODE_HASH,
                5
            )
        ).thenReturn(
            EmailVerificationConsumeResult.VERIFIED
        );

        when(
            passwordEncoder.encode(
                "Password1!"
            )
        ).thenReturn(
            "encoded-password"
        );

        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                " User@Example.com ",
                RAW_CODE,
                "Password1!"
            );

        // when
        service.registerLocalCredential(
            USER_ID,
            USER_ID,
            request
        );

        // then
        verify(registrationService)
            .register(
                USER_ID,
                "user@example.com",
                "encoded-password"
            );
    }

    @ParameterizedTest
    @EnumSource(
        value = EmailVerificationConsumeResult.class,
        names = {
            "NOT_FOUND",
            "INVALID",
            "ATTEMPTS_EXHAUSTED"
        }
    )
    @DisplayName("유효하지 않은 인증 상태는 동일한 400 오류로 처리한다")
    void registerLocalCredential_rejectsInvalidVerification(
        EmailVerificationConsumeResult consumeResult
    ) {
        // given
        prepareRegistrationRequest();

        when(
            verificationStore.consume(
                USER_ID,
                "user@example.com",
                CODE_HASH,
                5
            )
        ).thenReturn(
            consumeResult
        );

        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                RAW_CODE,
                "Password1!"
            );

        // when
        BusinessException exception =
            catchThrowableOfType(
                () ->
                    service.registerLocalCredential(
                        USER_ID,
                        USER_ID,
                        request
                    ),
                BusinessException.class
            );

        // then
        assertThat(exception.getErrorCode())
            .isEqualTo(
                ErrorCode.EMAIL_VERIFICATION_INVALID
            );

        verify(
            passwordEncoder,
            never()
        ).encode(
            "Password1!"
        );

        verifyNoInteractions(
            registrationService
        );
    }

    @Test
    @DisplayName("인증 성공 후에만 비밀번호를 BCrypt로 인코딩한다")
    void registerLocalCredential_encodesPasswordAfterVerification() {
        // given
        prepareRegistrationRequest();

        when(
            verificationStore.consume(
                USER_ID,
                "user@example.com",
                CODE_HASH,
                5
            )
        ).thenReturn(
            EmailVerificationConsumeResult.VERIFIED
        );

        when(
            passwordEncoder.encode(
                "Password1!"
            )
        ).thenReturn(
            "encoded-password"
        );

        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                RAW_CODE,
                "Password1!"
            );

        // when
        service.registerLocalCredential(
            USER_ID,
            USER_ID,
            request
        );

        // then
        org.mockito.InOrder inOrder =
            org.mockito.Mockito.inOrder(
                verificationStore,
                passwordEncoder,
                registrationService
            );

        inOrder.verify(verificationStore)
            .consume(
                USER_ID,
                "user@example.com",
                CODE_HASH,
                5
            );

        inOrder.verify(passwordEncoder)
            .encode(
                "Password1!"
            );

        inOrder.verify(registrationService)
            .register(
                USER_ID,
                "user@example.com",
                "encoded-password"
            );
    }

    private void prepareRegistrationRequest() {
        when(
            userRepository.findById(USER_ID)
        ).thenReturn(
            Optional.of(
                oauthOnlyUser(false)
            )
        );

        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        ).thenReturn(false);

        when(
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                RAW_CODE
            )
        ).thenReturn(CODE_HASH);
    }

    private void prepareIssueRequest() {
        when(
            userRepository.findById(USER_ID)
        ).thenReturn(
            Optional.of(
                oauthOnlyUser(false)
            )
        );

        when(
            userRepository.existsByEmail(
                "user@example.com"
            )
        ).thenReturn(false);

        when(
            verificationCodeGenerator.generate()
        ).thenReturn(RAW_CODE);

        when(
            verificationCodeHasher.hash(
                USER_ID,
                "user@example.com",
                RAW_CODE
            )
        ).thenReturn(CODE_HASH);
    }

    private LocalCredentialEmailVerificationRequest request() {
        return new LocalCredentialEmailVerificationRequest(
            "user@example.com"
        );
    }

    private User oauthOnlyUser(
        boolean locked
    ) {
        return User.builder()
            .email(
                "google-user@oauth.invalid"
            )
            .passwordHash(null)
            .name("OAuth 사용자")
            .profileImageUrl(null)
            .role(UserRole.USER)
            .locked(locked)
            .build();
    }

    private User oauthOnlyUserWithActualEmail(
        boolean locked
    ) {
        return User.builder()
            .email("user@example.com")
            .passwordHash(null)
            .name("Google OAuth 사용자")
            .profileImageUrl(null)
            .role(UserRole.USER)
            .locked(locked)
            .build();
    }

    private User localUser() {
        return User.builder()
            .email(
                "local@example.com"
            )
            .passwordHash(
                "encoded-password"
            )
            .name("로컬 사용자")
            .profileImageUrl(null)
            .role(UserRole.USER)
            .locked(false)
            .build();
    }
}
