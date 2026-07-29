package com.mopl.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * JWT 실구현은 아직 채워지지 않은 골격입니다.
 * TODO(빌드 주차): jjwt로 서명·만료·클레임 파싱을 구현하고, secret/만료 시간은 설정에서 주입합니다.
 * 지금은 validate가 항상 false라 필터가 인증을 세팅하지 않습니다. 다만 SecurityConfig가 현재 anyRequest를 permitAll로
 * 열어 두었기 때문에 실제로는 모든 요청이 통과합니다. 이후 인가를 .authenticated()로 잠그면, 그때부터 공개 경로 외에는 막힙니다.
 */

/**
 * JWT 액세스 토큰을 발급하고 검증하며
 * 유효한 토큰에서 Spring Security 인증 정보를 복원
 *
 * 토큰 subject에는 사용자 UUID를, role 클레임에는 사용자 역할을 저장
 */
@Component
@RequiredArgsConstructor
public class JwtProviderImpl implements JwtProvider {

    private static final String ROLE_CLAIM = "role";

    private final JwtProperties jwtProperties;

    // 사용자 ID와 역할을 담은 액세스 토큰을 발급
    @Override
    public String createAccessToken(UUID userId, String role) {
        Instant now = Instant.now();
        Instant expiration = now.plus(jwtProperties.getAccessTokenExpiration());

        return Jwts.builder()
            .issuer(jwtProperties.getIssuer())
            .subject(userId.toString())
            .claim(ROLE_CLAIM, role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiration))
            .signWith(signingKey())
            .compact();
    }

    /**
     * 토큰의 서명과 만료 시간을 검증
     *
     * 형식이 잘못됐거나, 서명이 다르거나, 만료된 경우 false를 반환
     * 예외를 밖으로 던지지 않아 JWT 필터가 안전하게 다음 필터로 넘길 수 있음.
     */
    @Override
    public boolean validate(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 검증된 토큰의 사용자 ID와 역할을 Spring Security 인증 정보로 복원
     *
     * principal은 사용자 UUID 문자열이며,
     * 컨트롤러에서 authentication.getName() 또는 principal.getName()으로 조회 가능
     */
    @Override
    public Authentication getAuthentication(String token) {
        Claims claims = parseClaims(token);

        String role = claims.get(ROLE_CLAIM, String.class);

        if (role == null || role.isBlank()) {
            throw new JwtException("JWT에 사용자 역할 정보가 없습니다.");
        }

        return new UsernamePasswordAuthenticationToken(
            claims.getSubject(),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
    }

    /**
     * JWT 문자열을 파싱해 Claims를 반환
     *
     * verifyWith()이 토큰의 서명을 검증하고
     * parseSignedClaims()가 만료 시간도 함께 검증
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    /**
     * 환경 변수의 Base64 비밀키를 HMAC 서명용 키로 변환
     */
    private SecretKey signingKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtProperties.getSecret());

        return Keys.hmacShaKeyFor(keyBytes);
    }
}
