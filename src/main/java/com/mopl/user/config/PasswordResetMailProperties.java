package com.mopl.user.config;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 비밀번호 초기화 이메일 작성에 필요한 설정값을 관리
 *
 * <p>{@code application.yml}의 {@code password-reset.mail} 설정을
 * 자바 객체로 바인딩합니다. 실제 SMTP 서버 접속 정보는 Spring Boot의
 * {@code spring.mail} 설정이 담당하고, 이 클래스는 비밀번호 초기화 메일에
 * 사용되는 발신 주소와 제목을 담당합니다.</p>
 *
 * <p>외부 환경변수로 잘못된 주소나 제목이 전달되면 실제 요청 처리 중에
 * 메일 발송이 실패할 수 있습니다. {@link Validated}를 사용하여 설정 오류를
 * 애플리케이션 시작 시점에 발견하도록 합니다.</p>
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(
    prefix = "password-reset.mail"
)
public class PasswordResetMailProperties {

    /**
     * 임시 비밀번호 안내 이메일의 발신 주소
     *
     * <p>SMTP 서비스에서 발신이 허용된 주소를 사용해야 합니다.
     * 공백, 잘못된 이메일 형식 및 비정상적으로 긴 값을 시작 시점에
     * 거부합니다.</p>
     */
    @NotBlank(
        message = "비밀번호 초기화 메일 발신 주소는 비어 있을 수 없습니다."
    )
    @Email(
        message = "비밀번호 초기화 메일 발신 주소가 올바른 이메일 형식이 아닙니다."
    )
    @Size(
        max = 254,
        message = "비밀번호 초기화 메일 발신 주소는 254자를 초과할 수 없습니다."
    )
    private String fromAddress;

    /**
     * 임시 비밀번호 안내 이메일의 제목
     *
     * <p>메일 제목은 비어 있을 수 없으며, 메일 헤더를 추가로 삽입할 수 있는
     * CR 또는 LF 줄바꿈 문자를 허용하지 않습니다.</p>
     */
    @NotBlank(
        message = "비밀번호 초기화 메일 제목은 비어 있을 수 없습니다."
    )
    @Size(
        max = 100,
        message = "비밀번호 초기화 메일 제목은 100자를 초과할 수 없습니다."
    )
    @Pattern(
        regexp = "^[^\\r\\n]+$",
        message = "비밀번호 초기화 메일 제목에는 줄바꿈 문자를 사용할 수 없습니다."
    )
    private String subject;
}
