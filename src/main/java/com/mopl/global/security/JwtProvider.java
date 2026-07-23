package com.mopl.global.security;

import org.springframework.security.core.Authentication;

import java.util.UUID;

/**
 * JWT 토큰을 발급·검증·해석하는 역할의 계약(인터페이스)입니다.
 * 실제 서명/파싱 구현은 빌드 주차에 jjwt로 채웁니다(JwtProviderImpl 참고).
 * 인터페이스로 두는 이유는, 필터·서비스가 구현 세부사항이 아니라 이 계약에만 의존하게 하기 위함입니다.
 */
public interface JwtProvider {

    /** 액세스 토큰을 발급합니다. */
    String createAccessToken(UUID userId, String role);

    /** 토큰이 유효한지(서명·만료) 검증합니다. */
    boolean validate(String token);

    /** 토큰에서 인증 정보(Authentication)를 추출합니다. */
    Authentication getAuthentication(String token);
}
