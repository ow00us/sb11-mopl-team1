package com.mopl.user.mail;

import com.mopl.user.config.OAuthLocalCredentialMailProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Spring Mail을 사용해 이메일 인증 코드를 발송하는 구현체
 */
@Component
@RequiredArgsConstructor
public class SpringMailEmailVerificationCodeSender
    implements EmailVerificationCodeSender {

    /**
     * Spring Boot의 spring.mail 설정으로 구성된 SMTP 발송기
     */
    private final JavaMailSender javaMailSender;

    /**
     * 인증 메일 발신 주소와 제목 설정
     */
    private final OAuthLocalCredentialMailProperties
        mailProperties;

    /**
     * 이메일 인증 코드 안내 메일을 평문으로 발송
     *
     * <p>메일 발송 중 발생한 예외를 숨기지 않고 상위 서비스에 전달합니다.
     * 인증 상태 저장과 메일 발송의 보상 처리는 서비스 계층에서 담당합니다.</p>
     */
    @Override
    public void send(
        String recipientEmail,
        String verificationCode,
        Duration expiration
    ) {
        SimpleMailMessage message =
            new SimpleMailMessage();

        message.setFrom(
            mailProperties.getFromAddress()
        );

        message.setTo(
            recipientEmail
        );

        message.setSubject(
            mailProperties.getSubject()
        );

        message.setText(
            createBody(
                verificationCode,
                expiration
            )
        );

        /*
         * 인증 코드 원문이 예외 메시지나 로그에 포함되지 않도록
         * 별도의 catch 또는 로깅을 하지 않는다.
         */
        javaMailSender.send(message);
    }

    /**
     * 인증 코드 안내용 평문 이메일 본문을 생성
     *
     * @param verificationCode 6자리 인증 코드 원문
     * @param expiration 인증 코드 유효 시간
     * @return 인증 코드 안내 이메일 본문
     */
    private String createBody(
        String verificationCode,
        Duration expiration
    ) {
        return """
            안녕하세요. 모두의 플리입니다.

            이메일·비밀번호 로그인 수단 추가를 위한 인증 코드입니다.

            인증 코드: %s
            유효 시간: %d초

            인증 코드는 한 번만 사용할 수 있으며,
            최대 시도 횟수를 초과하면 더 이상 사용할 수 없습니다.

            본인이 요청하지 않았다면 이 이메일을 무시해 주세요.
            """
            .formatted(
                verificationCode,
                expiration.toSeconds()
            );
    }
}
