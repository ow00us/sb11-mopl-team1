package com.mopl.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 이메일/비밀번호 로그인 API가 받는 요청 데이터
 *
 * 회원가입과 같은 비밀번호 정책을 적용하여
 * BCrypt가 처리할 수 있는 범위를 벗어난 입력이 인증 단꼐까지 전달되지 않게 함.
 */

public record SignInRequest (

    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 100, message = "이메일은 100자 이하로 작성 가능합니다.")
    String email,

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, max = 72, message = "비밀번호는 8~72자로 작성 가능합니다.")
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*()_+\\-={}\\[\\]|:;\"'<>,.?/~`])"
            + "[A-Za-z\\d!@#$%^&*()_+\\-={}\\[\\]|:;\"'<>,.?/~`]+$",
        message = "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
    )
    String password
) {

}
