package com.mopl.user.security.oauth.handler;

import com.mopl.user.config.OAuthRedirectProperties;
import com.mopl.user.cookie.RefreshTokenCookieFactory;
import com.mopl.user.security.oauth.MoplOAuth2User;
import com.mopl.user.service.IssuedRefreshToken;
import com.mopl.user.service.RefreshTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * OAuth2 인증 성공 후 Refresh Token을 발급하고
 * 프론트엔드 OAuth Callback 화면으로 Redirect하는 Handler
 *
 * <p>Access Token과 Refresh Token은 Redirect URL에 포함하지 않습니다.
 * Refresh Token은 HttpOnly Cookie로 전달하고, 프론트엔드는 Redirect 이후
 * 기존 토큰 재발급 API를 호출하여 Access Token과 사용자 정보를 받습니다.</p>
 */
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler
    implements AuthenticationSuccessHandler {

    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final OAuthRedirectProperties redirectProperties;
    private final OAuth2AuthenticationFailureHandler failureHandler;

    /**
     * OAuth2 인증 성공 결과를 처리
     *
     * <ol>
     *     <li>인증 Principal이 MoplOAuth2User인지 확인합니다.</li>
     *     <li>사용자 UUID를 기준으로 Refresh Token을 발급합니다.</li>
     *     <li>Refresh Token 원문을 HttpOnly Cookie로 변환합니다.</li>
     *     <li>Set-Cookie 응답 헤더에 Cookie를 추가합니다.</li>
     *     <li>프론트엔드 OAuth Callback 경로로 Redirect합니다.</li>
     * </ol>
     */
    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication
    ) throws IOException, ServletException {
        Object principal = authentication.getPrincipal();

        /*
         * Provider별 OAuth2UserService가 공통 Principal로 변환하지 않은 경우
         * 사용자 UUID를 신뢰할 수 없으므로 토큰을 발급하지 않는다.
         */
        if (!(principal instanceof MoplOAuth2User oAuth2User)) {
            failureHandler.onAuthenticationFailure(
                request,
                response,
                new AuthenticationServiceException(
                    "지원하지 않는 OAuth2 Principal입니다."
                )
            );
            return;
        }

        final ResponseCookie refreshTokenCookie;

        try {
            /*
             * Refresh Token 원문은 Redis에 저장하지 않고,
             * RefreshTokenService가 해시만 저장
             */
            IssuedRefreshToken issuedRefreshToken =
                refreshTokenService.issue(
                    oAuth2User.getUserId()
                );

            refreshTokenCookie =
                refreshTokenCookieFactory.create(
                    issuedRefreshToken.rawToken()
                );
        } catch (RuntimeException exception) {
            /*
             * OAuth Provider 인증은 성공했더라도 내부 사용자 세션 발급에
             * 실패하면 로그인 성공으로 Redirect하지 않는다.
             */
            failureHandler.onAuthenticationFailure(
                request,
                response,
                new AuthenticationServiceException(
                    "OAuth2 로그인 세션을 생성할 수 없습니다.",
                    exception
                )
            );
            return;
        }

        response.addHeader(
            HttpHeaders.SET_COOKIE,
            refreshTokenCookie.toString()
        );

        /*
         * 토큰을 Redirect URL에 넣지 않습니다.
         * URL은 브라우저 방문 기록, 프록시 로그와 Referer 등에 남을 수 있다.
         */
        response.sendRedirect(
            redirectProperties
                .getSuccessUri()
                .toASCIIString()
        );
    }
}
