package com.mopl.user.service;

import java.time.Instant;

/**
 * Refresh Token 발급 결과를 표현하는 내부 객체
 *
 * rawToken은 이후 로그인 연동 과정에서 HttpOnly Cookie로 전달할 원문이고
 * expiresAt은 Cookie 만료 시각과 서버 세션의 만료 정보를 맞추는 데 사용
 *
 * 이 객체는 API 응답 DTO가 아니며, Refresh Token 발급 Service와
 * 로그인 연동 Service 사이에서만 사용하는 내부 데이터
 *
 * @param rawToken 클라이언트에 전달할 Refresh Token 원문
 * @param expiresAt Refresh Token의 절대 만료 시각
 */
public record IssuedRefreshToken(
    String rawToken,
    Instant expiresAt
) {
    /**
     * 객체 전체가 실수로 로그에 기록되더라도
     * Refresh Token 원문이 노출되지 않도록 마스킹
     *
     * rawToken() 접근자는 이후 Cookie 생성 과정에서 그대로 사용할 수 있지만,
     * record가 자동으로 생성하는 toString()에는 원문을 포함하지 않는다.
     */
    @Override
    public String toString() {
        return "IssuedRefreshToken[rawToken=***, expiresAt="
            + expiresAt
            + "]";
    }
}
