package com.mopl.user.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth 인증 성공·실패 후 이동할 프론트엔드 Redirect URI 설정
 *
 * <p>Redirect 목적지를 요청 파라미터로 전달받으면 공격자가 임의 사이트로
 * 사용자를 이동시키는 Open Redirect 취약점이 발생할 수 있습니다.
 * 따라서 Redirect URI는 서버 설정에서만 관리합니다.</p>
 *
 * <p>설정값은 애플리케이션 시작 시 검증하여 OAuth 인증이 끝난 뒤에야
 * 잘못된 URI가 발견되는 상황을 방지합니다.</p>
 */
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(
    prefix = "app.oauth2.redirect"
)
public class OAuthRedirectProperties {

    /**
     * OAuth 인증 성공 후 이동할 프론트엔드 Callback URI
     *
     * <p>프론트엔드는 이 화면에 진입한 뒤 기존
     * POST /api/auth/refresh를 호출하여 Access Token과
     * 사용자 정보를 복원합니다.</p>
     */
    @NotNull(
        message =
            "OAuth 성공 Redirect URI는 필수입니다."
    )
    private URI successUri;

    /**
     * OAuth 인증 실패 후 이동할 프론트엔드 URI
     *
     * <p>실패 Handler는 내부 예외 메시지를 전달하지 않고
     * 일반화된 오류 코드만 이 URI에 추가합니다.</p>
     */
    @NotNull(
        message =
            "OAuth 실패 Redirect URI는 필수입니다."
    )
    private URI failureUri;

    /**
     * 성공 Redirect URI가 안전한 HTTP 또는 HTTPS 절대 URI인지 검증
     *
     * @return 성공 Redirect URI가 유효하면 true
     */
    @AssertTrue(
        message =
            "OAuth 성공 Redirect URI는 쿼리와 Fragment가 없는 올바른 HTTP 또는 HTTPS 절대 URI여야 합니다."
    )
    public boolean isSuccessUriValid() {
        return isValidRedirectUri(successUri);
    }

    /**
     * 실패 Redirect URI가 안전한 HTTP 또는 HTTPS 절대 URI인지 검증
     *
     * @return 실패 Redirect URI가 유효하면 true
     */
    @AssertTrue(
        message =
            "OAuth 실패 Redirect URI는 쿼리와 Fragment가 없는 올바른 HTTP 또는 HTTPS 절대 URI여야 합니다."
    )
    public boolean isFailureUriValid() {
        return isValidRedirectUri(failureUri);
    }

    /**
     * Redirect URI의 공통 보안 조건을 확인
     *
     * <p>상대 경로와 javascript 등의 스킴은 허용하지 않고,
     * HTTP 또는 HTTPS URI만 허용합니다.</p>
     *
     * <p>User Info, Query와 Fragment를 설정값에 허용하면
     * Redirect 조합 과정이 복잡해지고 사용자 정보가 의도치 않게
     * 전달될 수 있으므로 허용하지 않습니다.</p>
     *
     * <p>null은 @NotNull이 별도로 검증하므로 중복 오류 메시지가
     * 발생하지 않도록 여기서는 true를 반환합니다.</p>
     *
     * @param uri 검증할 Redirect URI
     * @return 안전한 Redirect URI이면 true
     */
    private boolean isValidRedirectUri(URI uri) {
        if (uri == null) {
            return true;
        }

        String scheme = uri.getScheme();

        boolean supportedScheme =
            "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme);

        return uri.isAbsolute()
            && supportedScheme
            && uri.getHost() != null
            && uri.getUserInfo() == null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null;
    }
}
