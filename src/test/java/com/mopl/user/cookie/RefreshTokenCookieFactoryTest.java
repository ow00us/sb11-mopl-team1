package com.mopl.user.cookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.user.config.RefreshTokenCookieProperties;
import com.mopl.user.config.RefreshTokenProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/**
 * RefreshTokenCookieFactory가 Refresh Token Cookie 정책을
 * 정확하게 적용하는지 검증하는 단위 테스트
 *
 * <p>Spring ApplicationContext를 실행하지 않고 설정 객체를 직접 준비하여
 * Cookie 생성 규칙만 빠르게 검증합니다.</p>
 */
class RefreshTokenCookieFactoryTest {

    RefreshTokenCookieProperties cookieProperties;
    RefreshTokenProperties refreshTokenProperties;
    RefreshTokenCookieFactory cookieFactory;

    @BeforeEach
    void setUp() {
        /*
         * 로컬 개발 환경의 기본 Refresh Token Cookie 정책을 준비
         */
        cookieProperties =
            new RefreshTokenCookieProperties();

        cookieProperties.setName("REFRESH_TOKEN");
        cookieProperties.setPath("/api/auth");
        cookieProperties.setSameSite("Lax");
        cookieProperties.setSecure(false);

        /*
         * Redis 세션 TTL과 Cookie Max-Age에 공통으로 적용할
         * Refresh Token 유효기간을 준비
         */
        refreshTokenProperties =
            new RefreshTokenProperties();

        refreshTokenProperties.setExpiration(
            Duration.ofDays(7)
        );

        cookieFactory =
            new RefreshTokenCookieFactory(
                cookieProperties,
                refreshTokenProperties
            );
    }

    @Test
    @DisplayName("Refresh Token 원문으로 HttpOnly Cookie를 생성한다")
    void create_success() {
        // given
        String rawToken = "generated-refresh-token";

        // when
        ResponseCookie cookie =
            cookieFactory.create(rawToken);

        // then
        assertThat(cookie.getName())
            .isEqualTo("REFRESH_TOKEN");

        assertThat(cookie.getValue())
            .isEqualTo(rawToken);

        assertThat(cookie.isHttpOnly())
            .isTrue();

        assertThat(cookie.isSecure())
            .isFalse();

        assertThat(cookie.getSameSite())
            .isEqualTo("Lax");

        assertThat(cookie.getPath())
            .isEqualTo("/api/auth");

        assertThat(cookie.getMaxAge())
            .isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("운영 설정에서는 Refresh Token Cookie에 Secure를 적용한다")
    void create_appliesSecureProperty() {
        // given
        cookieProperties.setSecure(true);

        // when
        ResponseCookie cookie =
            cookieFactory.create(
                "generated-refresh-token"
            );

        // then
        assertThat(cookie.isSecure())
            .isTrue();

        /*
         * Secure 설정이 달라져도 Refresh Token을 JavaScript에
         * 노출하지 않도록 HttpOnly는 계속 유지되어야 한다.
         */
        assertThat(cookie.isHttpOnly())
            .isTrue();
    }

    @Test
    @DisplayName("Refresh Token 원문이 null이면 Cookie를 생성하지 않는다")
    void create_rejectsNullToken() {
        assertThatThrownBy(() ->
            cookieFactory.create(null)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Refresh Token 원문은 비어 있을 수 없습니다."
            );
    }

    @Test
    @DisplayName("Refresh Token 원문이 빈 문자열이면 Cookie를 생성하지 않는다")
    void create_rejectsBlankToken() {
        assertThatThrownBy(() ->
            cookieFactory.create(" ")
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "Refresh Token 원문은 비어 있을 수 없습니다."
            );
    }
}
