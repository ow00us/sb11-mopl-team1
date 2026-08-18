package com.mopl.global.security;

import com.mopl.user.entity.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;


/**
 * JWT 액세스 토큰을 발급하고 검증하며
 * 유효한 토큰에서 Spring Security 인증 정보를 복원
 *
 * 토큰 subject에는 사용자 UUID를, role 클레임에는 사용자 역할을 저장
 *
 * 애플리케이션 시작 시 1회 Base64 디코딩 → SecretKey 생성
 * 요청마다 캐시된 SecretKey로 토큰 검증
 */
@Component
public class JwtProviderImpl implements JwtProvider {

    /**
     * JWT 서명에 사용하는 고정 JCA 알고리즘 이름
     *
     * <p>Secret 길이에 따라 JJWT가 HS384 또는 HS512를 자동 선택하지
     * 않도록 서비스의 JWT 계약인 HS256을 명시적으로 사용합니다.</p>
     */
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private static final String ROLE_CLAIM = "role";

    private final JwtProperties jwtProperties;

    // 애플리케이션 실행 동안 바뀌지 않는 HMAC 서명 키를 한 번만 생성해 재사용합니다.
    private final SecretKey signingKey;

    /**
     * JWT 설정값을 받아 HMAC 서명 키를 한 번 생성합니다.
     *
     * 잘못된 Base64 값 또는 너무 짧은 비밀키는 애플리케이션 시작 시점에 발견됩니다.
     */
    public JwtProviderImpl(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.signingKey = createSigningKey(jwtProperties.getSecret());
    }

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
            .signWith(signingKey)
            .compact();
    }

    /**
     * 토큰의 서명, 만료 시간, issuer, subject, role 클레임을 검증
     *
     * 검증에 실패한 토큰은 예외를 외부로 전달하지 않고 false를 반환
     * JwtAuthenticationFilter는 false인 경우 인증 정보를 SecurityContext에 저장하지 않음
     */
    @Override
    public boolean validate(String token) {
        try {
            parseAndValidateToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 검증 규칙을 통과한 토큰을 Spring Security Authentication으로 변환
     *
     * principal에는 UUID 객체를 넣어 이후 도메인 코드가 사용자 식별값으로 사용할 수 있게 함
     * 권한은 Spring Security 관례에 맞게 ROLE_USER 또는 ROLE_ADMIN 형태로 변환
     */
    @Override
    public Authentication getAuthentication(String token) {
        TokenClaims tokenClaims = parseAndValidateToken(token);

        return UsernamePasswordAuthenticationToken.authenticated(
            tokenClaims.userId(),
            null,
            List.of(
                new SimpleGrantedAuthority(
                    "ROLE_" + tokenClaims.role().name()
                )
            )
        );
    }

    /**
     * 서명된 JWT를 파싱하고, 애플리케이션이 요구하는 클레임 규칙까지 검증
     *
     * requireIssuer는 다른 발급자가 만든 정상 서명 토큰을 허용하지 않게 함
     * subject와 role은 파싱 가능한 값인지 별도로 확인
     */
    private TokenClaims parseAndValidateToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(jwtProperties.getIssuer())
            .build()
            .parseSignedClaims(token)
            .getPayload();

        // parseSignedClaims()는 만료된 exp는 검증하지만 exp 클레임 자체가 없는 토큰은 별도로 거절
        if (claims.getExpiration() == null) {
            throw new JwtException("JWT에 만료 시간 정보가 없습니다.");
        }

        UUID userId = parseUserId(claims.getSubject());
        UserRole role = parseUserRole(claims.get(ROLE_CLAIM, String.class));

        return new TokenClaims(userId, role);
    }

    /**
     * JWT subject가 비어 있지 않은 UUID 문자열인지 검증
     */
    private UUID parseUserId(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new JwtException("JWT에 사용자 식별자가 없습니다.");
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new JwtException("JWT 사용자 식별자 형식이 올바르지 않습니다.");
        }
    }

    /**
     * role 클레임이 현재 서비스에서 허용하는 UserRole 값인지 검증
     */
    private UserRole parseUserRole(String role) {
        if (role == null || role.isBlank()) {
            throw new JwtException("JWT에 사용자 역할 정보가 없습니다.");
        }

        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException exception) {
            throw new JwtException("JWT 사용자 역할 값이 올바르지 않습니다.");
        }
    }

    /**
     * JWT에서 검증을 마친 사용자 식별값과 역할을 보관하는 내부 값 객체
     *
     * <p>파싱된 Claims 전체를 외부로 전달하지 않고,
     * Authentication 생성에 필요한 사용자 UUID와 역할만 전달합니다.</p>
     *
     * @param userId JWT subject에서 복원한 사용자 UUID
     * @param role   JWT role 클레임에서 복원한 사용자 역할
     */
    private record TokenClaims(
        UUID userId,
        UserRole role
    ) {
    }

    /**
     * Base64 형식의 비밀키를 HS256 서명용 SecretKey로 변환
     *
     * <p>{@code Keys.hmacShaKeyFor()}는 키 길이에 따라 HS256, HS384,
     * HS512 중 하나를 자동으로 선택합니다. 그러면 배포 환경에 주입된
     * Secret 길이에 따라 JWT 헤더의 alg 값이 달라질 수 있습니다.</p>
     *
     * <p>이 서비스의 JWT 계약은 HS256이므로 Secret 길이와 관계없이
     * JCA 알고리즘을 HmacSHA256으로 명시합니다. Secret은
     * {@link JwtProperties}에서 Base64 형식과 최소 32바이트 길이를
     * 애플리케이션 시작 시점에 검증합니다.</p>
     *
     * @param secret Base64로 인코딩된 JWT Secret
     * @return HS256 서명과 검증에 사용할 SecretKey
     */
    private static SecretKey createSigningKey(String secret) {
        byte[] keyBytes =
            Decoders.BASE64.decode(secret);

        /*
         * 키 길이에 따른 JJWT의 자동 알고리즘 선택을 사용하지 않고,
         * 서비스 계약인 HmacSHA256을 명시적으로 지정
         *
         * 32바이트보다 긴 Secret도 전체 바이트를 키 재료로 사용하며
         * 서명 알고리즘만 HS256으로 고정
         */
        return new SecretKeySpec(
            keyBytes,
            HMAC_SHA_256
        );
    }
}
