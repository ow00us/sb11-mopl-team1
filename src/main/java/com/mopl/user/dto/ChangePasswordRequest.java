package com.mopl.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 사용자 비밀번호 변경 요청의 JSON 데이터를 전달하는 DTO
 *
 * PATCH /api/users/{userId}/password 요청의 request body를 표현
 *
 * 현재 Swagger 계약에는 기존 비밀번호가 포함되어 있지 않으므로
 * 이번 기본 구현에서는 새로 변경할 비밀번호만 전달받는다.
 *
 * 비밀번호 정책은 회원가입의 UserCreateRequest와 동일하게 유지
 */
public record ChangePasswordRequest(

    /**
     * 새로 변경할 비밀번호
     *
     * BCrypt는 비밀번호 입력을 최대 72바이트까지만 안전하게 처리하므로
     * 현재 프로젝트는 ASCII 문자만 허용하면서 최대 길이를 72자로 제한
     *
     * @NotBlank:
     * null, 빈 문자열, 공백만 있는 문자열을 거부
     *
     * @Size:
     * 비밀번호 길이를 8자 이상 72자 이하로 제한
     *
     * @Pattern:
     * 영문, 숫자, 허용된 특수문자를 각각 하나 이상 포함하도록 하고,
     * 한글, 이모지, 공백 및 허용되지 않은 문자를 거부
     */
    @NotBlank(message = "비밀번호를 입력해주세요.")
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
}
