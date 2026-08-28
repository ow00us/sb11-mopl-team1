package com.mopl.user.service;

/**
 * Access Token 사용자 인증 상태 확인 결과
 */
public enum AccessTokenAuthenticationStatus {

    /**
     * 인증을 계속 허용할 수 있음
     */
    ALLOWED,

    /**
     * 탈퇴·잠금 또는 Redis 차단 상태로 인증을 거부해야 함
     */
    BLOCKED,

    /**
     * Redis와 데이터베이스가 모두 실패하여
     * 사용자 상태를 안전하게 확인할 수 없음
     */
    UNAVAILABLE
}
