package com.mopl.sample.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 요청 바디를 받는 DTO 예시입니다. record로 정의하고, 입력값 검증은 Bean Validation(@NotBlank 등)으로 답니다.
 * 검증에 실패하면 GlobalExceptionHandler가 400 응답과 함께 어떤 필드가 왜 틀렸는지를 details에 담아 줍니다.
 */
public record SampleCreateRequest(
        @NotBlank(message = "이름은 필수입니다.")
        String name
) {
}
