package com.mopl.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * OAuth 전용 사용자가 로컬 이메일·비밀번호 로그인 수단을
 * 추가하기 위해 이메일 인증 코드를 요청하는 DTO
 *
 * @param email 소유권을 확인할 실제 이메일
 */
public record LocalCredentialEmailVerificationRequest(

    /**
     * 로컬 로그인 ID로 사용할 실제 이메일
     */
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
    String email

) {

    /**
     * Bean Validation 전에 입력값 앞뒤 공백을 제거
     *
     * <p>대소문자 정규화는 사용자 조회 및 저장 규칙을 알고 있는
     * 서비스 계층에서 수행합니다.</p>
     */
    public LocalCredentialEmailVerificationRequest {
        if (email != null) {
            email = email.strip();
        }
    }
}
