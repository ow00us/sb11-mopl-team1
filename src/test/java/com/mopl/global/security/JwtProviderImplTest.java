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

        // validate()가 true인 토큰은 getAuthentication()으로 안전하게 변환할 수 있어야 함
        assertThat(jwtProvider.validate(token)).isTrue();

        Authentication authentication = jwtProvider.getAuthentication(token);

        // JWT subject에 저장한 사용자 UUID가 인증 주체 이름으로 복원
        assertThat(authentication.getName()).isEqualTo(userId.toString());

        // validate()가 true를 반환한 토큰은 getAuthentication()에서 안전하게 Authentication으로 변환
        assertThat(authentication.getPrincipal()).isEqualTo(userId);

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

    @Test
    @DisplayName("issuer가 다르면 유효하지 않은 토큰으로 처리한다")
    void validate_fail_whenIssuerIsDifferent() {
        String token = createSignedToken(
            "another-service",
            UUID.randomUUID().toString(),
            "USER"
        );

        assertThat(jwtProvider.validate(token)).isFalse();
    }

    @Test
    @DisplayName("subject가 UUID 형식이 아니면 유효하지 않은 토큰으로 처리한다")
    void validate_fail_whenSubjectIsNotUuid() {
        String token = createSignedToken(
            "mopl",
            "not-a-uuid",
            "USER"
        );

        assertThat(jwtProvider.validate(token)).isFalse();
    }

    @Test
    @DisplayName("허용되지 않은 role이면 유효하지 않은 토큰으로 처리한다")
    void validate_fail_whenRoleIsNotAllowed() {
        String token = createSignedToken(
            "mopl",
            UUID.randomUUID().toString(),
            "SUPER_ADMIN"
        );

        assertThat(jwtProvider.validate(token)).isFalse();
    }

    @Test
    @DisplayName("role 클레임이 없으면 유효하지 않은 토큰으로 처리한다")
    void validate_fail_whenRoleIsMissing() {
        SecretKey signingKey = Keys.hmacShaKeyFor(
            Decoders.BASE64.decode(TEST_SECRET)
        );

        String token = Jwts.builder()
            .issuer("mopl")
            .subject(UUID.randomUUID().toString())
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(60)))
            .signWith(signingKey)
            .compact();

        assertThat(jwtProvider.validate(token)).isFalse();
    }

    /**
     * 특정 클레임 값을 가진 서명 토큰을 만들어 검증 실패 상황을 테스트
     *
     * 운영 토큰을 위조하는 코드가 아닌
     * 테스트 전용 비밀키로 잘못된 클레임을 가진 토큰을 만드는 헬퍼
     */
    private String createSignedToken(
        String issuer,
        String subject,
        String role
    ) {
        SecretKey signingKey = Keys.hmacShaKeyFor(
            Decoders.BASE64.decode(TEST_SECRET)
        );

        Instant now = Instant.now();

        return Jwts.builder()
            .issuer(issuer)
            .subject(subject)
            .claim("role", role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(60)))
            .signWith(signingKey)
            .compact();
    }
}
