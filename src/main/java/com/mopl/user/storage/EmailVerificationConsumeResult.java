package com.mopl.user.storage;

/**
 * 이메일 인증 코드 검증 및 일회성 소비 결과
 */
public enum EmailVerificationConsumeResult {

    /**
     * 사용자, 이메일 및 인증 코드가 일치하여 인증 상태를 소비함
     */
    VERIFIED,

    /**
     * 인증 상태가 존재하지 않거나 이미 만료됨
     */
    NOT_FOUND,

    /**
     * 인증 코드 또는 대상 이메일이 일치하지 않음
     */
    INVALID,

    /**
     * 최대 실패 횟수에 도달하여 인증 상태를 폐기함
     */
    ATTEMPTS_EXHAUSTED
}
