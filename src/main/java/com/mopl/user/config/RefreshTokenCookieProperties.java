package com.mopl.user.config;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
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
@Validated
@ConfigurationProperties(prefix = "refresh-token.cookie")
public class RefreshTokenCookieProperties {

    /**
     * OpenAPI와 인증 API에서 사용하는 Refresh Token Cookie 이름
     *
     * <p>Cookie를 발급하는 코드와 재발급 요청에서 Cookie를 읽는 코드가
     * 서로 다른 이름을 사용하면 로그인 후 재발급이 항상 실패합니다.
     * 따라서 하나의 상수를 공통 계약으로 사용합니다.</p>
     */
    public static final String REQUIRED_COOKIE_NAME =
        "REFRESH_TOKEN";

    /**
     * 클라이언트에 전달할 Refresh Token Cookie 이름
     *
     * <p>기존 OpenAPI의 토큰 재발급 계약에서 사용하는
     * {@code REFRESH_TOKEN}과 동일하게 설정합니다.</p>
     */
    @NotBlank(
        message = "Refresh Token Cookie 이름은 비어 있을 수 없습니다."
    )
    @Pattern(
        regexp = "^" + REQUIRED_COOKIE_NAME + "$",
        message = "Refresh Token Cookie 이름은 REFRESH_TOKEN이어야 합니다."
    )
    private String name;

    /**
     * Refresh Token Cookie가 전송될 요청 경로
     *
     * <p>{@code /api/auth}로 제한하면 로그인, 토큰 재발급과 로그아웃 등
     * 인증 API에서만 Cookie가 전송되고 다른 도메인 API에는 불필요하게
     * 포함되지 않습니다.</p>
     */
    @NotBlank(message = "Refresh Token Cookie 경로는 비어 있을 수 없습니다.")
    @Pattern(
        regexp = "^/[A-Za-z0-9/_-]*$",
        message = "Refresh Token Cookie 경로는 /로 시작하는 올바른 경로여야 합니다."
    )
    private String path;

    /**
     * 브라우저의 교차 사이트 Cookie 전송 정책
     *
     * <p>로컬 개발과 동일 사이트 배포에서는 Lax를 기본값으로 사용합니다.
     * 프론트엔드와 백엔드가 서로 다른 사이트에 배포되는 경우에는
     * 운영 환경에서 None으로 변경하고 Secure를 함께 사용해야 합니다.</p>
     */
    @NotBlank(message = "Refresh Token Cookie SameSite 값은 비어 있을 수 없습니다.")
    @Pattern(
        regexp = "Strict|Lax|None",
        message = "Refresh Token Cookie SameSite는 Strict, Lax 또는 None이어야 합니다."
    )
    private String sameSite;

    /**
     * HTTPS 연결에서만 Cookie를 전송할지 결정
     *
     * <p>로컬 HTTP 개발 환경에서는 false가 필요하지만,
     * 운영 HTTPS 환경에서는 true를 사용합니다.</p>
     */
    private boolean secure;

    /**
     * SameSite=None Cookie는 교차 사이트 요청에 사용되므로
     * HTTPS에서만 전송되도록 Secure 속성이 반드시 필요
     *
     * <p>잘못된 운영 환경 변수가 설정되면 브라우저가 Cookie를 거부할 수 있으므로
     * 애플리케이션 시작 시점에 설정 오류로 차단합니다.</p>
     *
     * @return SameSite와 Secure 설정 조합이 유효하면 true
     */
    @AssertTrue(
        message = "Refresh Token Cookie의 SameSite가 None이면 Secure는 true여야 합니다."
    )
    public boolean isSecureValidForSameSite() {
        /*
         * sameSite가 null인 경우에는 @NotBlank가 별도로 처리
         * "None".equals(...) 형태를 사용하면 null에서도 예외가 발생하지 않는다.
         */
        return !"None".equals(sameSite) || secure;
    }
}
