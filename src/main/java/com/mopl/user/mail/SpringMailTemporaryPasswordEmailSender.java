package com.mopl.user.mail;

import com.mopl.user.config.PasswordResetMailProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Spring Mail을 사용해 임시 비밀번호 안내 이메일을 발송하는 구현체
 *
 * <p>{@link TemporaryPasswordEmailSender} 인터페이스의 실제 SMTP 기반
 * 구현체입니다. 이메일 발신 주소와 제목은
 * {@link PasswordResetMailProperties}에서 가져오고, 메일 전송은
 * {@link JavaMailSender}에 위임합니다.</p>
 *
 * <p>임시 비밀번호 원문은 이메일 본문을 작성하는 용도로만 사용합니다.
 * 보안상 데이터베이스, Redis 또는 애플리케이션 로그에 원문을 저장하거나
 * 출력하지 않습니다.</p>
 *
 * <p>메일 발송 중 발생하는 예외를 이 클래스에서 처리하거나 숨기지 않습니다.
 * 상위 비밀번호 초기화 서비스가 트랜잭션 안에서 이 발송기를 호출하면
 * 메일 발송 실패가 서비스까지 전달되어 비밀번호 변경 트랜잭션을
 * 롤백할 수 있습니다.</p>
 */
@Component
@RequiredArgsConstructor
public class SpringMailTemporaryPasswordEmailSender
    implements TemporaryPasswordEmailSender {

    /**
     * Spring Boot가 {@code spring.mail} 설정으로 구성한 메일 발송기
     */
    private final JavaMailSender javaMailSender;

    /**
     * 비밀번호 초기화 메일의 발신 주소와 제목 설정
     */
    private final PasswordResetMailProperties mailProperties;

    /**
     * 사용자에게 임시 비밀번호 안내 이메일을 발송
     *
     * <p>{@link SimpleMailMessage}는 HTML이 아닌 평문 이메일을 작성합니다.
     * 임시 비밀번호 안내에는 이미지나 HTML 기능이 필요하지 않으므로
     * 단순한 평문 형식을 사용하여 구현 복잡도와 HTML 삽입 위험을 줄입니다.</p>
     *
     * @param recipientEmail 임시 비밀번호를 받을 사용자 이메일
     * @param temporaryPassword 생성된 임시 비밀번호 원문
     */
    @Override
    public void send(
        String recipientEmail,
        String temporaryPassword
    ) {
        SimpleMailMessage message =
            new SimpleMailMessage();

        /*
         * 발신 주소와 제목은 application.yml 및 환경변수로 관리
         * 코드에 운영용 이메일 주소를 직접 작성하지 않는다.
         */
        message.setFrom(
            mailProperties.getFromAddress()
        );

        message.setTo(
            recipientEmail
        );

        message.setSubject(
            mailProperties.getSubject()
        );

        /*
         * 임시 비밀번호는 반드시 이메일 본문에만 포함한다.
         * 이 값을 로그로 출력하거나 예외 메시지에 포함하면 안된다.
         */
        message.setText(
            createBody(
                temporaryPassword
            )
        );

        /*
         * MailException은 RuntimeException이므로 별도의 catch로 숨기지 않는다.
         * 발송 실패를 상위 서비스까지 전달해야 비밀번호 변경 트랜잭션을
         * 롤백할 수 있다.
         */
        javaMailSender.send(message);
    }

    /**
     * 임시 비밀번호 안내용 평문 이메일 본문을 생성
     *
     * <p>사용자가 임시 비밀번호를 확인한 뒤 로그인하고 즉시 비밀번호를
     * 변경할 수 있도록 안내 문구를 포함합니다.</p>
     *
     * @param temporaryPassword 이메일에 표시할 임시 비밀번호 원문
     * @return 임시 비밀번호 안내 이메일 본문
     */
    private String createBody(
        String temporaryPassword
    ) {
        return """
            안녕하세요. 모두의 플리입니다.

            비밀번호 초기화 요청에 따라 임시 비밀번호가 발급되었습니다.

            임시 비밀번호: %s

            위 임시 비밀번호로 로그인한 후 안전한 비밀번호로 변경해 주세요.

            본인이 요청하지 않았다면 서비스 관리자에게 문의해 주세요.
            """
            .formatted(temporaryPassword);
    }
}
