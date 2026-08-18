package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.dto.ResetPasswordRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.mail.TemporaryPasswordEmailSender;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.TemporaryPasswordGenerator;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;

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

        when(
            userRepository.findByEmail(
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
            .findByEmail(
                normalizedEmail
            );

        verify(
            temporaryPasswordGenerator
        ).generate();

        verify(passwordEncoder)
            .encode(
                temporaryPassword
            );

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
    @DisplayName("존재하지 않는 이메일이면 404 비즈니스 예외를 발생시킨다")
    void resetPassword_fail_whenUserDoesNotExist() {
        // given
        String normalizedEmail =
            "missing@example.com";

        ResetPasswordRequest request =
            new ResetPasswordRequest(
                "MISSING@EXAMPLE.COM"
            );

        when(
            userRepository.findByEmail(
                normalizedEmail
            )
        ).thenReturn(
            Optional.empty()
        );

        // when & then
        assertThatThrownBy(
            () ->
                passwordResetService
                    .resetPassword(request)
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(
                        exception.getErrorCode()
                    ).isEqualTo(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
            );

        /*
         * 사용자가 없으면 임시 비밀번호 생성, 인코딩 및
         * 이메일 발송을 수행해서는 안된다.
         */
        verifyNoInteractions(
            temporaryPasswordGenerator,
            passwordEncoder,
            temporaryPasswordEmailSender
        );

        verify(userRepository)
            .findByEmail(
                normalizedEmail
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

        MailSendException mailSendException =
            new MailSendException(
                "SMTP 서버 연결 실패"
            );

        when(
            userRepository.findByEmail(email)
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
        return User.builder()
            .email(email)
            .passwordHash(passwordHash)
            .name("테스트 사용자")
            .role(UserRole.USER)
            .locked(false)
            .build();
    }
}
