package com.mopl.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 초기화 요청의 JSON 데이터를 전달하는 DTO
 *
 * <p>OpenAPI의 POST /api/auth/reset-password 요청 본문을 표현합니다.</p>
 *
 * <p>사용자는 임시 비밀번호를 전달받을 이메일 주소만 입력합니다.
 * Controller는 {@code @Valid}를 통해 이 DTO의 Bean Validation 규칙을
 * 실행하고, 검증에 실패하면 Service를 호출하지 않고 400 Bad Request를
 * 반환합니다.</p>
 *
 * @param email 임시 비밀번호를 전달받을 사용자 이메일
 */
public record ResetPasswordRequest(

    /**
     * 임시 비밀번호를 전달받을 이메일
     *
     * <p>{@link NotBlank}는 null, 빈 문자열과 공백만 있는 문자열을
     * 거부합니다.</p>
     *
     * <p>{@link Email}은 이메일 기본 형식을 검증합니다.</p>
     *
     * <p>{@link Size}는 users.email 컬럼과 회원가입 요청의 정책에 맞춰
     * 최대 길이를 100자로 제한합니다.</p>
     */
    @Schema(
        description = "임시 비밀번호를 발급받을 이메일",
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
     * Bean Validation 실행 전에 이메일 앞뒤 공백을 제거
     *
     * <p>이메일을 복사하거나 붙여넣는 과정에서 발생할 수 있는
     * 앞뒤 공백은 제거합니다. 이메일 내부 공백은 제거하지 않으며
     * {@link Email} 검증에서 잘못된 형식으로 처리합니다.</p>
     *
     * <p>이 단계에서는 대소문자를 변경하지 않습니다. 이메일 소문자
     * 정규화는 사용자 조회를 담당하는 Service에서 수행합니다.</p>
     */
    public ResetPasswordRequest {
        if (email != null) {
            email = email.strip();
        }
    }
}
