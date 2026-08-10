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
}
