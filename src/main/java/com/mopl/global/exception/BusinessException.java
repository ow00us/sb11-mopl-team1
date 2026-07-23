package com.mopl.global.exception;

import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 우리 서비스의 도메인 예외들이 상속해서 쓰는 기반 예외입니다.
 * ErrorCode 하나만 넘기면 HTTP 상태·코드·메시지가 함께 실려 나갑니다.
 * 특정 필드에 대한 부가 설명이 필요하면 addDetail로 덧붙이실 수 있습니다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, String> details;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.details = new HashMap<>();
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = new HashMap<>();
    }

    public BusinessException addDetail(String key, String value) {
        this.details.put(key, value);
        return this;
    }
}
