package com.mopl.user.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.mopl.user.config.PasswordResetMailProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Spring Mail 기반 임시 비밀번호 이메일 발송 동작을 검증
 *
 * <p>실제 SMTP 서버에 연결하지 않고 JavaMailSender를 Mock으로 대체합니다.
 * 이를 통해 수신자, 발신자, 제목과 본문이 올바르게 구성되는지 빠르게
 * 검증할 수 있습니다.</p>
 */
@ExtendWith(MockitoExtension.class)
class SpringMailTemporaryPasswordEmailSenderTest {

    /**
     * 실제 SMTP 통신을 대신하는 Mock 메일 발송기
     */
    @Mock
    JavaMailSender javaMailSender;

    /**
     * 테스트 대상 구현체에 전달할 메일 설정
     */
    PasswordResetMailProperties mailProperties;

    /**
     * 테스트 대상 임시 비밀번호 이메일 발송기
     */
    SpringMailTemporaryPasswordEmailSender emailSender;

    @BeforeEach
    void setUp() {
        mailProperties =
            new PasswordResetMailProperties();

        mailProperties.setFromAddress(
            "no-reply@mopl.local"
        );

        mailProperties.setSubject(
            "[모두의 플리] 임시 비밀번호 안내"
        );

        emailSender =
            new SpringMailTemporaryPasswordEmailSender(
                javaMailSender,
                mailProperties
            );
    }

    @Test
    @DisplayName("임시 비밀번호 안내 이메일을 올바른 내용으로 발송한다")
    void send_success() {
        // given
        String recipientEmail =
            "user@example.com";

        String temporaryPassword =
            "Abcd2345!TestPwd";

        ArgumentCaptor<SimpleMailMessage>
            messageCaptor =
            ArgumentCaptor.forClass(
                SimpleMailMessage.class
            );

        // when
        emailSender.send(
            recipientEmail,
            temporaryPassword
        );

        // then
        /*
         * JavaMailSender로 실제 전달된 SimpleMailMessage를 캡처하여
         * 각 이메일 필드를 검증
         */
        verify(javaMailSender)
            .send(
                messageCaptor.capture()
            );

        SimpleMailMessage capturedMessage =
            messageCaptor.getValue();

        assertThat(
            capturedMessage.getFrom()
        ).isEqualTo(
            "no-reply@mopl.local"
        );

        assertThat(
            capturedMessage.getTo()
        ).containsExactly(
            recipientEmail
        );

        assertThat(
            capturedMessage.getSubject()
        ).isEqualTo(
            "[모두의 플리] 임시 비밀번호 안내"
        );

        /*
         * 임시 비밀번호와 비밀번호 변경 안내가 모두 본문에
         * 포함되어 있는지 확인
         */
        assertThat(
            capturedMessage.getText()
        )
            .contains(temporaryPassword)
            .contains(
                "로그인한 후 안전한 비밀번호로 변경"
            );
    }

    @Test
    @DisplayName("메일 발송 실패 예외를 숨기지 않고 상위 호출자에게 전달한다")
    void send_propagatesMailSendException() {
        // given
        MailSendException mailSendException =
            new MailSendException(
                "SMTP 서버 연결 실패"
            );

        /*
         * JavaMailSender가 메일 발송 중 실패하는 상황을 재현
         */
        doThrow(mailSendException)
            .when(javaMailSender)
            .send(
                any(SimpleMailMessage.class)
            );

        // when & then
        /*
         * 예외를 내부에서 삼키지 않아야 상위 서비스의 트랜잭션이
         * 실패하고 비밀번호 변경도 롤백될 수 있다.
         */
        assertThatThrownBy(
            () ->
                emailSender.send(
                    "user@example.com",
                    "Abcd2345!TestPwd"
                )
        ).isSameAs(mailSendException);
    }
}
