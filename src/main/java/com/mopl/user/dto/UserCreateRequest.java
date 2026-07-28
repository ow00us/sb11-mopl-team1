package com.mopl.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청에서 받는 데이터
 * 프론트엔드 요청 JSON 받기 위한 DTO
 * Controller가 이 DTO 받을 때 Bean Validation으로 입력값 검증
 */
public record UserCreateRequest (
    @NotBlank(message = "이름을 입력해주세요.")
    @Size(max = 30, message = "이름은 30자 이하로 작성 가능합니다.")
    String name,

    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    @Size(max = 100, message = "이메일은 100자 이하로 작성 가능합니다.")
    String email,

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, max = 72, message = "비밀번호는 8~72자로 작성 가능합니다.")
    @Pattern(
        regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9\\s])\\S+$",
        message = "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
    )
    String password
) {
    /**
     * Bean Validation 전에 이메일의 앞뒤 공백을 제거
     *
     * " user@example.com "처럼 복사·붙여넣기 과정에서 생긴 공백은 허용하되,
     * 이메일 내부 공백은 @Email 검증에서 거절
     */
    public UserCreateRequest {
        if (email != null) {
            email = email.strip();
        }
    }
}
