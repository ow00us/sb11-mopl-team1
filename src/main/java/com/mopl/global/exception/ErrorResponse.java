package com.mopl.global.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 클라이언트로 내려가는 공통 에러 응답 형태입니다.
 * 기존 프론트 계약(exceptionName·message·details)을 그대로 지키면서,
 * 분기용으로 더 안정적인 errorCode 필드를 추가했습니다.
 */
@Schema(description = "공통 오류 응답")
public record ErrorResponse(
        @Schema(description = "발생한 예외 이름", example = "BusinessException") String exceptionName,
        @Schema(description = "애플리케이션 오류 코드", example = "COMMON_400_1") String errorCode,
        @Schema(description = "사용자에게 전달할 오류 메시지") String message,
        @Schema(description = "필드 오류 등 추가 정보") Map<String, String> details
) {
    public static ErrorResponse of(String exceptionName, ErrorCode errorCode, String message, Map<String, String> details) {
        return new ErrorResponse(exceptionName, errorCode.getCode(), message, details);
    }
}
