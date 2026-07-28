package com.mopl.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

/**
 * JWT 발급, 검증, 인증 정보 복원 로직을 검증
 *
 * 실제 환경 변수 대신 테스트 전용 Base64 비밀키를 사용
 * 이 값은 테스트용 공개 값이므로 운영 비밀키와 무관
 */
class JwtProviderImplTest {

    private static final String TEST_SECRET =
        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private JwtProviderImpl jwtProvider;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("mopl");
        jwtProperties.setSecret(TEST_SECRET);
        jwtProperties.setAccessTokenExpiration(Duration.ofMinutes(30));

        jwtProvider = new JwtProviderImpl(jwtProperties);
    }

    @Test
    @DisplayName("액세스 토큰을 발급하면 검증할 수 있고 사용자 인증 정보를 복원한다")
    void createAccessToken_success() {
        // given
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        // when
        String token = jwtProvider.createAccessToken(userId, "USER");
        Authentication authentication = jwtProvider.getAuthentication(token);

        // then
        assertThat(jwtProvider.validate(token)).isTrue();

        // JWT subject에 저장한 사용자 UUID가 인증 주체 이름으로 복원
        assertThat(authentication.getName()).isEqualTo(userId.toString());

        // JWT role 클레임이 Spring Security 권한 형식인 ROLE_USER로 복원
        assertThat(authentication.getAuthorities())
            .extracting(authority -> authority.getAuthority())
            .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("서명이 변경된 토큰은 유효하지 않다")
    void validate_fail_whenTokenIsTampered() {
        // given
        String token = jwtProvider.createAccessToken(UUID.randomUUID(), "USER");
        String tamperedToken = token + "changed";

        // when & then
        assertThat(jwtProvider.validate(tamperedToken)).isFalse();
    }

    @Test
    @DisplayName("만료 시간이 지난 토큰은 유효하지 않다")
    void validate_fail_whenTokenIsExpired() {
        // given
        SecretKey signingKey = Keys.hmacShaKeyFor(
            Decoders.BASE64.decode(TEST_SECRET)
        );

        String expiredToken = Jwts.builder()
            .issuer("mopl")
            .subject(UUID.randomUUID().toString())
            .claim("role", "USER")
            .issuedAt(Date.from(Instant.now().minusSeconds(120)))
            .expiration(Date.from(Instant.now().minusSeconds(60)))
            .signWith(signingKey)
            .compact();

        // when & then
        assertThat(jwtProvider.validate(expiredToken)).isFalse();
    }
}
