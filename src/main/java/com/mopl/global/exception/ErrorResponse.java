package com.mopl.global.exception;

import java.util.Map;

/**
 * 클라이언트로 내려가는 공통 에러 응답 형태입니다.
 * 기존 프론트 계약(exceptionName·message·details)을 그대로 지키면서,
 * 분기용으로 더 안정적인 errorCode 필드를 추가했습니다.
 */
public record ErrorResponse(
        String exceptionName,
        String errorCode,
        String message,
        Map<String, String> details
) {
    public static ErrorResponse of(String exceptionName, ErrorCode errorCode, String message, Map<String, String> details) {
        return new ErrorResponse(exceptionName, errorCode.getCode(), message, details);
    }
}
