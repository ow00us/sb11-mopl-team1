package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.mopl.user.dto.ResetPasswordRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.mail.TemporaryPasswordEmailSender;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.TemporaryPasswordGenerator;
import com.mopl.user.storage.RefreshTokenStore;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 비밀번호 초기화 서비스의 비즈니스 흐름을 검증하는 단위 테스트
 *
 * <p>실제 PostgreSQL이나 SMTP 서버를 사용하지 않고 Repository,
 * PasswordEncoder, 임시 비밀번호 생성기와 이메일 발송기를 Mock으로
 * 대체하여 서비스가 각 협력 객체를 올바른 순서와 값으로 사용하는지
 * 검증합니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    TemporaryPasswordGenerator
        temporaryPasswordGenerator;

    @Mock
    TemporaryPasswordEmailSender
        temporaryPasswordEmailSender;

    @Mock
    RefreshTokenStore refreshTokenStore;

    @InjectMocks
    PasswordResetService passwordResetService;

    @Test
    @DisplayName("이메일을 정규화하고 임시 비밀번호 해시를 저장한 뒤 이메일로 발송한다")
    void resetPassword_success() {
        // given
        String normalizedEmail =
            "user@example.com";

        String temporaryPassword =
            "Abcd2345!TestPwd";

        String encodedPassword =
            "$2a$10$encodedTemporaryPassword";

        ResetPasswordRequest request =
            new ResetPasswordRequest(
                "USER@EXAMPLE.COM"
            );

        User user =
            createUser(
                normalizedEmail,
                "$2a$10$oldPasswordHash"
            );

        UUID userId =
            user.getId();

        when(
            userRepository.findByEmailForUpdate(
                normalizedEmail
            )
        ).thenReturn(
            Optional.of(user)
        );

        when(
            temporaryPasswordGenerator.generate()
        ).thenReturn(
            temporaryPassword
        );

        when(
            passwordEncoder.encode(
                temporaryPassword
            )
        ).thenReturn(
            encodedPassword
        );

        // when
        passwordResetService.resetPassword(
            request
        );

        // then
        /*
         * 대문자로 입력된 이메일이 소문자로 정규화되어
         * Repository 조회와 이메일 발송에 사용되는지 확인
         */
        verify(userRepository)
            .findByEmailForUpdate(
                normalizedEmail
            );

        verify(
            temporaryPasswordGenerator
        ).generate();

        verify(passwordEncoder)
            .encode(
                temporaryPassword
            );

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);

        verify(
            temporaryPasswordEmailSender
        ).send(
            normalizedEmail,
            temporaryPassword
        );

        /*
         * 엔티티에는 임시 비밀번호 원문이 아니라
         * 인코딩된 해시만 반영되어야 한다.
         */
        assertThat(
            user.getPasswordHash()
        ).isEqualTo(
            encodedPassword
        );

        assertThat(
            user.getPasswordHash()
        ).isNotEqualTo(
            temporaryPassword
        );

        /*
         * 영속 엔티티 변경 감지를 사용하므로 Repository에
         * 별도의 save 호출이 없어야 한다.
         */
        verifyNoMoreInteractions(
            userRepository
        );
    }

    @Test
    @DisplayName("로컬 로그인 수단이 없는 OAuth 전용 사용자는 작업 없이 종료한다")
    void resetPassword_ignore_whenLocalCredentialDoesNotExist() {
        // given
        String email =
            "oauth-user@example.com";

        ResetPasswordRequest request =
            new ResetPasswordRequest(email);

        User oauthOnlyUser =
            createUser(
                email,
                null
            );

        when(
            userRepository.findByEmailForUpdate(email)
        ).thenReturn(
            Optional.of(oauthOnlyUser)
        );

        // when
        passwordResetService.resetPassword(request);

        // then
        verify(userRepository)
            .findByEmailForUpdate(email);

        /*
         * OAuth 전용 사용자는 별도의 이메일 인증 기반 등록 절차를
         * 거쳐야 하므로 비밀번호 초기화 작업을 진행하지 않는다.
         */
        verifyNoInteractions(
            temporaryPasswordGenerator,
            passwordEncoder,
            temporaryPasswordEmailSender,
            refreshTokenStore
        );

        verifyNoMoreInteractions(
            userRepository
        );
    }

    @Test
    @DisplayName("존재하지 않거나 탈퇴한 사용자의 요청은 작업 없이 정상 종료한다")
    void resetPassword_ignoresMissingOrDeletedUser() {
        // given
        String normalizedEmail =
            "missing@example.com";

        ResetPasswordRequest request =
            new ResetPasswordRequest(
                "MISSING@EXAMPLE.COM"
            );

        when(
            userRepository.findByEmailForUpdate(
                normalizedEmail
            )
        ).thenReturn(
            Optional.empty()
        );

        // when
        passwordResetService.resetPassword(request);

        // then
        verify(userRepository)
            .findByEmailForUpdate(
                normalizedEmail
            );

        /*
         * 존재하지 않거나 탈퇴한 사용자는 임시 비밀번호 생성,
         * 인코딩, 세션 폐기 및 이메일 발송을 수행하지 않는다.
         */
        verifyNoInteractions(
            temporaryPasswordGenerator,
            passwordEncoder,
            temporaryPasswordEmailSender,
            refreshTokenStore
        );

        verifyNoMoreInteractions(
            userRepository
        );
    }

    @Test
    @DisplayName("이메일 발송이 실패하면 예외를 숨기지 않고 전달한다")
    void resetPassword_fail_whenEmailSendingFails() {
        // given
        String email =
            "user@example.com";

        String temporaryPassword =
            "Abcd2345!TestPwd";

        String encodedPassword =
            "$2a$10$encodedTemporaryPassword";

        ResetPasswordRequest request =
            new ResetPasswordRequest(email);

        User user =
            createUser(
                email,
                "$2a$10$oldPasswordHash"
            );

        UUID userId =
            user.getId();

        MailSendException mailSendException =
            new MailSendException(
                "SMTP 서버 연결 실패"
            );

        when(
            userRepository.findByEmailForUpdate(email)
        ).thenReturn(
            Optional.of(user)
        );

        when(
            temporaryPasswordGenerator.generate()
        ).thenReturn(
            temporaryPassword
        );

        when(
            passwordEncoder.encode(
                temporaryPassword
            )
        ).thenReturn(
            encodedPassword
        );

        doThrow(mailSendException)
            .when(
                temporaryPasswordEmailSender
            )
            .send(
                email,
                temporaryPassword
            );

        // when & then
        /*
         * MailSendException이 서비스 밖으로 전달되어야 실제 Spring
         * 트랜잭션 환경에서 비밀번호 변경이 롤백
         */
        assertThatThrownBy(
            () ->
                passwordResetService
                    .resetPassword(request)
        ).isSameAs(
            mailSendException
        );

        verify(passwordEncoder)
            .encode(
                temporaryPassword
            );

        verify(
            temporaryPasswordEmailSender
        ).send(
            email,
            temporaryPassword
        );

        /*
         * 이메일 발송 전에 세션 폐기가 완료
         *
         * 이메일 발송 실패로 비밀번호 DB 변경은 롤백되지만,
         * 보안상 기존 로그인 세션은 폐기된 상태를 유지
         */
        verify(refreshTokenStore)
            .revokeAllByUserId(userId);
    }

    @Test
    @DisplayName("Refresh Token 전체 세션 폐기에 실패하면 비밀번호와 이메일을 변경하지 않는다")
    void resetPassword_fail_whenRefreshTokenRevocationFails() {
        // given
        String email =
            "user@example.com";

        String oldPasswordHash =
            "$2a$10$oldPasswordHash";

        String temporaryPassword =
            "Abcd2345!TestPwd";

        String encodedPassword =
            "$2a$10$encodedTemporaryPassword";

        ResetPasswordRequest request =
            new ResetPasswordRequest(email);

        User user =
            createUser(
                email,
                oldPasswordHash
            );

        UUID userId =
            user.getId();

        when(
            userRepository.findByEmailForUpdate(email)
        ).thenReturn(
            Optional.of(user)
        );

        when(
            temporaryPasswordGenerator.generate()
        ).thenReturn(
            temporaryPassword
        );

        when(
            passwordEncoder.encode(
                temporaryPassword
            )
        ).thenReturn(
            encodedPassword
        );

        IllegalStateException redisException =
            new IllegalStateException(
                "Redis 세션 폐기 실패"
            );

        when(
            refreshTokenStore
                .revokeAllByUserId(userId)
        ).thenThrow(redisException);

        // when & then
        assertThatThrownBy(
            () ->
                passwordResetService
                    .resetPassword(request)
        ).isSameAs(redisException);

        /*
         * 세션 폐기가 실패했으므로 비밀번호 변경과
         * 이메일 발송까지 진행하면 안된다.
         */
        assertThat(
            user.getPasswordHash()
        ).isEqualTo(
            oldPasswordHash
        );

        verify(refreshTokenStore)
            .revokeAllByUserId(userId);

        verifyNoInteractions(
            temporaryPasswordEmailSender
        );
    }

    /**
     * 비밀번호 초기화 테스트에 사용할 사용자 엔티티 생성
     *
     * @param email 정규화된 사용자 이메일
     * @param passwordHash 기존 비밀번호 해시
     * @return 비밀번호 초기화 대상 사용자
     */
    private User createUser(
        String email,
        String passwordHash
    ) {
        User user =
            User.builder()
                .email(email)
                .passwordHash(passwordHash)
                .name("테스트 사용자")
                .role(UserRole.USER)
                .locked(false)
                .build();

        /*
         * 단위 테스트에서는 JPA가 UUID를 생성하지 않으므로
         * 실제 DB에서 조회된 사용자와 같은 상태를 만들기 위해
         * 테스트용 UUID를 직접 설정
         */
        ReflectionTestUtils.setField(
            user,
            "id",
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            )
        );

        return user;
    }
}
