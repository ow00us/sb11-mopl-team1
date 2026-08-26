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

    @Test
    @DisplayName("Refresh Token 삭제 Cookie에 빈 값과 Max-Age 0을 적용한다")
    void createDeletionCookie_success() {
        // given
        /*
         * 운영 환경에서도 삭제 Cookie가 기존 발급 Cookie와
         * 동일한 Secure 정책을 사용하는지 확인
         */
        cookieProperties.setSecure(true);

        ResponseCookie issuedCookie =
            cookieFactory.create(
                "generated-refresh-token"
            );

        // when
        ResponseCookie deletionCookie =
            cookieFactory.createDeletionCookie();

        // then
        assertThat(deletionCookie.getName())
            .isEqualTo(issuedCookie.getName())
            .isEqualTo("REFRESH_TOKEN");

        assertThat(deletionCookie.getValue())
            .isEmpty();

        /*
         * Max-Age가 0이면 브라우저는 Set-Cookie 응답을 받은 즉시
         * 기존 Cookie를 만료 처리
         */
        assertThat(deletionCookie.getMaxAge())
            .isEqualTo(Duration.ZERO);

        /*
         * 기존 Cookie와 동일한 경로를 사용해야 같은 이름의
         * Refresh Token Cookie가 정상적으로 삭제
         */
        assertThat(deletionCookie.getPath())
            .isEqualTo(issuedCookie.getPath())
            .isEqualTo("/api/auth");

        assertThat(deletionCookie.getSameSite())
            .isEqualTo(issuedCookie.getSameSite())
            .isEqualTo("Lax");

        assertThat(deletionCookie.isSecure())
            .isEqualTo(issuedCookie.isSecure())
            .isTrue();

        assertThat(deletionCookie.isHttpOnly())
            .isEqualTo(issuedCookie.isHttpOnly())
            .isTrue();
    }

    @Test
    @DisplayName("Refresh Token 삭제 Cookie는 Set-Cookie에 즉시 만료 속성을 포함한다")
    void createDeletionCookie_containsImmediateExpirationHeader() {
        // when
        ResponseCookie deletionCookie =
            cookieFactory.createDeletionCookie();

        // then
        /*
         * 객체의 Max-Age 값뿐 아니라 실제 HTTP Set-Cookie 헤더로
         * 직렬화했을 때도 Max-Age=0이 포함되는지 확인
         */
        assertThat(deletionCookie.toString())
            .contains("REFRESH_TOKEN=")
            .contains("Max-Age=0")
            .contains("Path=/api/auth")
            .contains("HttpOnly")
            .contains("SameSite=Lax");
    }
}
