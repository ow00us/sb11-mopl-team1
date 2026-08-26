package com.mopl.user.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.mopl.user.config.OAuthLocalCredentialMailProperties;
import java.time.Duration;
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
 * Spring Mail 기반 이메일 인증 코드 발송 동작을 검증
 */
@ExtendWith(MockitoExtension.class)
class SpringMailEmailVerificationCodeSenderTest {

    @Mock
    JavaMailSender javaMailSender;

    OAuthLocalCredentialMailProperties
        mailProperties;

    SpringMailEmailVerificationCodeSender
        emailSender;

    @BeforeEach
    void setUp() {
        mailProperties =
            new OAuthLocalCredentialMailProperties();

        mailProperties.setFromAddress(
            "no-reply@mopl.local"
        );

        mailProperties.setSubject(
            "[모두의 플리] 이메일 인증 코드 안내"
        );

        emailSender =
            new SpringMailEmailVerificationCodeSender(
                javaMailSender,
                mailProperties
            );
    }

    @Test
    @DisplayName("인증 코드 이메일을 올바른 내용으로 발송한다")
    void send_success() {
        // given
        String recipientEmail =
            "user@example.com";

        String verificationCode =
            "123456";

        Duration expiration =
            Duration.ofMinutes(10);

        ArgumentCaptor<SimpleMailMessage>
            messageCaptor =
            ArgumentCaptor.forClass(
                SimpleMailMessage.class
            );

        // when
        emailSender.send(
            recipientEmail,
            verificationCode,
            expiration
        );

        // then
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
            "[모두의 플리] 이메일 인증 코드 안내"
        );

        assertThat(
            capturedMessage.getText()
        )
            .contains(
                "인증 코드: 123456"
            )
            .contains(
                "유효 시간: 600초"
            )
            .contains(
                "한 번만 사용할 수 있으며"
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

        doThrow(mailSendException)
            .when(javaMailSender)
            .send(
                any(SimpleMailMessage.class)
            );

        // when & then
        assertThatThrownBy(
            () ->
                emailSender.send(
                    "user@example.com",
                    "123456",
                    Duration.ofMinutes(10)
                )
        ).isSameAs(mailSendException);
    }
}
