package com.mopl.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * OAuth 전용 사용자가 이메일 인증을 완료하고
 * 로컬 이메일·비밀번호 로그인 수단을 추가하는 요청 DTO
 *
 * @param email 인증 코드를 발급받은 실제 이메일
 * @param verificationCode 이메일로 전달받은 6자리 인증 코드
 * @param password 새로 등록할 로컬 로그인 비밀번호
 */
public record LocalCredentialRegistrationRequest(

    @Schema(
        description = "로컬 로그인 ID로 등록할 실제 이메일",
        format = "email",
        maxLength = 100,
        example = "user@example.com"
    )
    @NotBlank(
        message = "이메일을 입력해주세요."
    )
    @Email(
        message = "이메일 형식이 올바르지 않습니다."
    )
    @Size(
        max = 100,
        message = "이메일은 100자 이하로 작성 가능합니다."
    )
    String email,

    @Schema(
        description = "이메일로 전달받은 6자리 인증 코드",
        pattern = "^\\d{6}$",
        example = "123456"
    )
    @NotBlank(
        message = "인증 코드를 입력해주세요."
    )
    @Pattern(
        regexp = "^\\d{6}$",
        message = "인증 코드는 6자리 숫자여야 합니다."
    )
    String verificationCode,

    /**
     * 기존 회원가입 및 비밀번호 변경과 동일한 비밀번호 정책
     */
    @Schema(
        description = "8~72자의 새 비밀번호입니다. ASCII 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다.",
        minLength = 8,
        maxLength = 72
    )
    @NotBlank(
        message = "비밀번호를 입력해주세요."
    )
    @Size(
        min = 8,
        max = 72,
        message = "비밀번호는 8~72자로 작성 가능합니다."
    )
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)"
            + "(?=.*[!@#$%^&*()_+\\-={}\\[\\]|:;\"'<>,.?/~`])"
            + "[A-Za-z\\d!@#$%^&*()_+\\-={}\\[\\]|:;\"'<>,.?/~`]+$",
        message = "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
    )
    String password

) {

    /**
     * 이메일과 인증 코드의 복사·붙여넣기 과정에서 생긴
     * 앞뒤 공백을 제거
     *
     * <p>비밀번호는 공백 자체를 허용하지 않는 입력이므로 임의로
     * 변경하지 않고 원문 그대로 검증합니다.</p>
     */
    public LocalCredentialRegistrationRequest {
        if (email != null) {
            email = email.strip();
        }

        if (verificationCode != null) {
            verificationCode =
                verificationCode.strip();
        }
    }

    /**
     * 요청 객체가 로그에 기록되더라도 이메일, 인증 코드와
     * 비밀번호 원문이 노출되지 않도록 모든 필드를 마스킹
     *
     * @return 민감정보가 제거된 문자열 표현
     */
    @Override
    public String toString() {
        return "LocalCredentialRegistrationRequest["
            + "email=***, "
            + "verificationCode=***, "
            + "password=***"
            + "]";
    }
}
