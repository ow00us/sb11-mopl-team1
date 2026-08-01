package com.mopl.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 사용자 프로필 수정 요청의 JSON 데이터를 전달하는 DTO
 *
 * PATCH /api/users/{userId} 요청은 multipart/form-data 형식을 사용하며,
 * request 파트에 이 DTO를 JSON 형식으로 전달
 *
 * name은 선택값
 * 프로필 이미지만 변경하는 요청도 가능해야 하므로 @NotBlank는 사용하지 않음
 * 다만 이름이 전달되었다면 공백으로만 구성된 값은 허용하지 않음
 */
public record UserUpdateRequest(

    /**
     * 새로 변경할 사용자 이름
     *
     * null이면 기존 이름을 유지
     * 회원가입의 이름 정책과 동일하게 최대 30자까지 허용
     */
    @Size(
        max = 30,
        message = "이름은 30자 이하로 작성 가능합니다."
    )
    @Pattern(
        regexp = ".*\\S.*",
        message = "이름은 공백으로만 작성할 수 없습니다."
    )
    String name

) {
}
