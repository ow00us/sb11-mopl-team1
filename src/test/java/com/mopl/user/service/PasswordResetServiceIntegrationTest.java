package com.mopl.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.config.JpaConfig;
import com.mopl.user.dto.ResetPasswordRequest;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.mail.TemporaryPasswordEmailSender;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.security.TemporaryPasswordGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 비밀번호 초기화 서비스의 실제 PostgreSQL 트랜잭션 동작을 검증
 *
 * <p>단위 테스트는 협력 객체 호출과 예외 전달을 확인하지만,
 * 메일 발송 실패 시 데이터베이스 변경이 실제로 롤백되는지는
 * 검증할 수 없습니다.</p>
 *
 * <p>이 테스트는 Testcontainers PostgreSQL과 Spring 트랜잭션을 사용하여
 * 성공 시 password_hash가 변경되고, 메일 발송 실패 시 기존 해시가
 * 유지되는지 확인합니다.</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    PasswordResetService.class
})
@AutoConfigureTestDatabase(
    replace =
        AutoConfigureTestDatabase
            .Replace
            .NONE
)
@Testcontainers
@Transactional(
    propagation = Propagation.NOT_SUPPORTED
)
class PasswordResetServiceIntegrationTest {

    /**
     * 실제 운영 DB와 같은 PostgreSQL 기반으로 트랜잭션을 검증
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(
            "postgres:16"
        );

    /**
     * 실제 users 테이블에 테스트 사용자를 저장하고 다시 조회
     */
    @Autowired
    UserRepository userRepository;

    /**
     * 이번 테스트에서 검증할 실제 트랜잭션 서비스
     */
    @Autowired
    PasswordResetService passwordResetService;

    /**
     * 생성 결과를 고정하여 비밀번호 변경 값을 예측 가능하게 만든다.
     */
    @MockitoBean
    TemporaryPasswordGenerator
        temporaryPasswordGenerator;

    /**
     * SMTP 서버를 사용하지 않고 정상 발송과 발송 실패를 제어
     */
    @MockitoBean
    TemporaryPasswordEmailSender
        temporaryPasswordEmailSender;

    /**
     * 실제 BCrypt 연산 대신 고정된 해시를 반환하도록 Mock 처리
     *
     * <p>이 테스트의 목적은 BCrypt 알고리즘 자체가 아니라
     * 트랜잭션 커밋과 롤백 검증입니다.</p>
     */
    @MockitoBean
    PasswordEncoder passwordEncoder;

    /**
     * 각 테스트 시작 전 users 테이블을 초기화
     */
    @BeforeEach
    void cleanUpUsers() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("메일 발송에 성공하면 변경된 비밀번호 해시가 DB에 반영된다")
    void resetPassword_commits_whenEmailSendingSucceeds() {
        // given
        String email =
            "user@example.com";

        String oldPasswordHash =
            "old-encoded-password";

        String temporaryPassword =
            "Abcd2345!TestPwd";

        String newPasswordHash =
            "new-encoded-password";

        saveUser(
            email,
            oldPasswordHash
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
            newPasswordHash
        );

        // when
        passwordResetService.resetPassword(
            new ResetPasswordRequest(email)
        );

        // then
        /*
         * Service의 트랜잭션이 정상 커밋된 뒤 DB에서 사용자를
         * 다시 조회하여 password_hash 변경 결과를 확인
         */
        User updatedUser =
            userRepository
                .findByEmail(email)
                .orElseThrow();

        assertThat(
            updatedUser.getPasswordHash()
        ).isEqualTo(
            newPasswordHash
        );

        assertThat(
            updatedUser.getPasswordHash()
        ).isNotEqualTo(
            temporaryPassword
        );

        verify(
            temporaryPasswordEmailSender
        ).send(
            email,
            temporaryPassword
        );
    }

    @Test
    @DisplayName("메일 발송에 실패하면 비밀번호 해시 변경을 롤백한다")
    void resetPassword_rollsBack_whenEmailSendingFails() {
        // given
        String email =
            "user@example.com";

        String oldPasswordHash =
            "old-encoded-password";

        String temporaryPassword =
            "Abcd2345!TestPwd";

        String newPasswordHash =
            "new-encoded-password";

        saveUser(
            email,
            oldPasswordHash
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
            newPasswordHash
        );

        MailSendException mailSendException =
            new MailSendException(
                "SMTP 서버 연결 실패"
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
         * MailSendException은 RuntimeException이므로
         * PasswordResetService의 @Transactional이 롤백
         */
        assertThatThrownBy(
            () ->
                passwordResetService
                    .resetPassword(
                        new ResetPasswordRequest(
                            email
                        )
                    )
        ).isSameAs(
            mailSendException
        );

        /*
         * 서비스 트랜잭션이 끝난 뒤 DB에서 다시 조회
         *
         * 단위 테스트의 메모리 객체가 아니라 PostgreSQL에 저장된
         * 실제 password_hash 값을 확인
         */
        User unchangedUser =
            userRepository
                .findByEmail(email)
                .orElseThrow();

        assertThat(
            unchangedUser.getPasswordHash()
        ).isEqualTo(
            oldPasswordHash
        );

        assertThat(
            unchangedUser.getPasswordHash()
        ).isNotEqualTo(
            newPasswordHash
        );
    }

    /**
     * 통합 테스트에 사용할 사용자를 PostgreSQL에 저장
     *
     * @param email 사용자 이메일
     * @param passwordHash 기존 비밀번호 해시
     */
    private void saveUser(
        String email,
        String passwordHash
    ) {
        User user =
            User.builder()
                .email(email)
                .passwordHash(
                    passwordHash
                )
                .name("테스트 사용자")
                .role(UserRole.USER)
                .locked(false)
                .build();

        /*
         * INSERT SQL을 즉시 PostgreSQL에 반영하여
         * 서비스 실행 전 테스트 사용자가 확실히 존재하도록 한다.
         */
        userRepository.saveAndFlush(
            user
        );
    }
}
