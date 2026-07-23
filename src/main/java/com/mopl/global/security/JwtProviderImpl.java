package com.mopl.global.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * JWT 실구현은 아직 채워지지 않은 골격입니다.
 * TODO(빌드 주차): jjwt로 서명·만료·클레임 파싱을 구현하고, secret/만료 시간은 설정에서 주입합니다.
 * 지금은 validate가 항상 false라 필터가 인증을 세팅하지 않습니다. 다만 SecurityConfig가 현재 anyRequest를 permitAll로
 * 열어 두었기 때문에 실제로는 모든 요청이 통과합니다. 이후 인가를 .authenticated()로 잠그면, 그때부터 공개 경로 외에는 막힙니다.
 */
@Component
public class JwtProviderImpl implements JwtProvider {

    @Override
    public String createAccessToken(UUID userId, String role) {
        throw new UnsupportedOperationException("JWT 발급은 빌드 주차에 구현합니다.");
    }

    @Override
    public boolean validate(String token) {
        return false;
    }

    @Override
    public Authentication getAuthentication(String token) {
        throw new UnsupportedOperationException("JWT 파싱은 빌드 주차에 구현합니다.");
    }
}
