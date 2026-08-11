package com.mopl.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Refresh Token Cookie 생성에 필요한 설정값을 관리하는 클래스
 *
 * <p>application.yml의 {@code refresh-token.cookie} 설정을 바인딩
 * 로그인, 토큰 재발급과 로그아웃에서 동일한 Cookie 이름과 경로,
 * SameSite 및 Secure 정책을 사용하기 위해 설정을 한 곳으로 모읍니다.</p>
 *
 * <p>HttpOnly는 Refresh Token을 JavaScript에서 읽지 못하게 하는
 * 필수 보안 정책이므로 환경에 따라 변경하지 않고 Cookie 생성 코드에서
 * 항상 true로 적용합니다.</p>
 *
 * <p>Cookie의 Max-Age는 Refresh Token 서버 세션의 유효기간과
 * 반드시 같아야 하므로 별도의 값을 두지 않고
 * {@link RefreshTokenProperties#getExpiration()}을 사용합니다.</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "refresh-token.cookie")
public class RefreshTokenCookieProperties {

    /**
     * 클라이언트에 전달할 Refresh Token Cookie 이름
     *
     * <p>기존 OpenAPI의 토큰 재발급 계약에서 사용하는
     * {@code REFRESH_TOKEN}과 동일하게 설정합니다.</p>
     */
    private String name;

    /**
     * Refresh Token Cookie가 전송될 요청 경로
     *
     * <p>{@code /api/auth}로 제한하면 로그인, 토큰 재발급과 로그아웃 등
     * 인증 API에서만 Cookie가 전송되고 다른 도메인 API에는 불필요하게
     * 포함되지 않습니다.</p>
     */
    private String path;

    /**
     * 브라우저의 교차 사이트 Cookie 전송 정책
     *
     * <p>로컬 개발과 동일 사이트 배포에서는 Lax를 기본값으로 사용합니다.
     * 프론트엔드와 백엔드가 서로 다른 사이트에 배포되는 경우에는
     * 운영 환경에서 None으로 변경하고 Secure를 함께 사용해야 합니다.</p>
     */
    private String sameSite;

    /**
     * HTTPS 연결에서만 Cookie를 전송할지 결정
     *
     * <p>로컬 HTTP 개발 환경에서는 false가 필요하지만,
     * 운영 HTTPS 환경에서는 true를 사용합니다.</p>
     */
    private boolean secure;
}
