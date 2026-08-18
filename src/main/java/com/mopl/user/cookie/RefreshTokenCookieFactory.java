package com.mopl.user.cookie;

import com.mopl.user.config.RefreshTokenCookieProperties;
import com.mopl.user.config.RefreshTokenProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 원문을 브라우저에 전달할 HttpOnly Cookie로 변환하는 Factory
 *
 * <p>Controller가 Cookie 이름, 경로, SameSite, Secure와 Max-Age를
 * 직접 조합하지 않도록 Cookie 생성 책임을 분리합니다.</p>
 *
 * <p>로그인, 토큰 재발급과 로그아웃에서 같은 Cookie 속성을 사용해야
 * 브라우저가 동일한 Cookie로 인식하므로 공통 Factory에서 일관된
 * 속성을 적용합니다.</p>
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookieFactory {

    /**
     * Cookie 이름, 경로, SameSite와 Secure 정책을 제공하는 설정
     */
    private final RefreshTokenCookieProperties cookieProperties;

    /**
     * Redis Refresh Token 세션과 Cookie에 동일한 유효기간을 적용하기 위한 설정
     */
    private final RefreshTokenProperties refreshTokenProperties;

    /**
     * 발급된 Refresh Token 원문을 HttpOnly Cookie로 생성
     *
     * <p>Refresh Token 원문은 브라우저 Cookie 값으로 전달되어야 하지만
     * JavaScript에서는 읽을 수 없도록 HttpOnly를 항상 true로 설정합니다.</p>
     *
     * <p>Max-Age는 Redis에 저장한 Refresh Token 세션 TTL과 동일한
     * {@link RefreshTokenProperties#getExpiration()}을 사용합니다.
     * 이를 통해 브라우저 Cookie와 서버 세션의 수명이 서로 달라지는
     * 문제를 방지합니다.</p>
     *
     * @param rawToken 클라이언트에 전달할 Refresh Token 원문
     * @return 로그인 응답의 Set-Cookie 헤더에 사용할 ResponseCookie
     * @throws IllegalArgumentException Refresh Token 원문이 null이거나 비어 있는 경우
     */
    public ResponseCookie create(String rawToken) {
        /*
         * 비어 있는 Refresh Token Cookie가 클라이언트에 전달되면
         * 이후 모든 재발급 요청이 실패하므로 Cookie 생성 전에 차단
         */
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException(
                "Refresh Token 원문은 비어 있을 수 없습니다."
            );
        }

        /*
         * HttpOnly는 Refresh Token을 JavaScript에서 읽지 못하게 하는
         * 필수 정책이므로 환경 설정과 관계없이 항상 true로 적용
         *
         * Secure와 SameSite는 로컬 및 운영 배포 환경이 다를 수 있으므로
         * RefreshTokenCookieProperties의 값을 사용
         */
        return cookieBuilder(rawToken)
            .maxAge(
                refreshTokenProperties.getExpiration()
            )
            .build();
    }

    /**
     * 브라우저에 저장된 Refresh Token Cookie를 삭제하기 위한 Cookie를 생성
     *
     * <p>Cookie 값은 빈 문자열로 설정하고 Max-Age를 0으로 지정하여
     * 브라우저가 기존 Cookie를 즉시 만료시키도록 합니다.</p>
     *
     * <p>브라우저가 기존 Cookie와 같은 Cookie로 인식하려면 발급할 때와
     * 동일한 이름과 경로를 사용해야 합니다. Secure, SameSite와 HttpOnly도
     * 공통 Builder를 통해 기존 발급 정책과 동일하게 적용합니다.</p>
     *
     * @return 로그아웃 응답의 Set-Cookie 헤더에 사용할 삭제 Cookie
     */
    public ResponseCookie createDeletionCookie() {
        return cookieBuilder("")
            .maxAge(Duration.ZERO)
            .build();
    }

    /**
     * Refresh Token Cookie의 공통 보안 속성을 적용하는 Builder를 생성
     *
     * <p>발급 Cookie와 삭제 Cookie가 서로 다른 이름·경로·보안 속성을
     * 사용하는 실수를 막기 위해 공통 속성을 한 곳에서 구성합니다.</p>
     *
     * @param value Refresh Token Cookie에 저장할 값
     * @return 공통 Cookie 속성이 적용된 Builder
     */
    private ResponseCookie.ResponseCookieBuilder cookieBuilder(
        String value
    ) {
        return ResponseCookie.from(
                cookieProperties.getName(),
                value
            )
            .httpOnly(true)
            .secure(cookieProperties.isSecure())
            .sameSite(cookieProperties.getSameSite())
            .path(cookieProperties.getPath());
    }
}
