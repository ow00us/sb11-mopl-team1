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
 * OAuth 전용 사용자의 로컬 로그인 수단 추가 과정에서 사용하는
 * 이메일 인증 메일 설정
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(
    prefix = "app.oauth2.local-credential.mail"
)
public class OAuthLocalCredentialMailProperties {

    /**
     * 인증 코드 이메일의 발신 주소
     */
    @NotBlank(
        message = "OAuth 로컬 로그인 인증 메일 발신 주소는 비어 있을 수 없습니다."
    )
    @Email(
        message = "OAuth 로컬 로그인 인증 메일 발신 주소가 올바른 이메일 형식이 아닙니다."
    )
    @Size(
        max = 254,
        message = "OAuth 로컬 로그인 인증 메일 발신 주소는 254자를 초과할 수 없습니다."
    )
    private String fromAddress;

    /**
     * 인증 코드 이메일의 제목
     *
     * <p>메일 헤더 삽입을 방지하기 위해 CR/LF 문자를 허용하지 않습니다.</p>
     */
    @NotBlank(
        message = "OAuth 로컬 로그인 인증 메일 제목은 비어 있을 수 없습니다."
    )
    @Size(
        max = 100,
        message = "OAuth 로컬 로그인 인증 메일 제목은 100자를 초과할 수 없습니다."
    )
    @Pattern(
        regexp = "^[^\\r\\n]+$",
        message = "OAuth 로컬 로그인 인증 메일 제목에는 줄바꿈 문자를 사용할 수 없습니다."
    )
    private String subject;
}
