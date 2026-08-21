package com.mopl.user.security.oauth.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.user.config.OAuthRedirectProperties;
import com.mopl.user.cookie.RefreshTokenCookieFactory;
import com.mopl.user.security.oauth.MoplOAuth2User;
import com.mopl.user.service.IssuedRefreshToken;
import com.mopl.user.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock
    RefreshTokenService refreshTokenService;

    @Mock
    RefreshTokenCookieFactory refreshTokenCookieFactory;

    @Mock
    OAuthRedirectProperties redirectProperties;

    @Mock
    OAuth2AuthenticationFailureHandler failureHandler;

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    @Mock
    Authentication authentication;

    @Mock
    MoplOAuth2User oAuth2User;

    OAuth2AuthenticationSuccessHandler successHandler;

    @BeforeEach
    void setUp() {
        successHandler =
            new OAuth2AuthenticationSuccessHandler(
                refreshTokenService,
                refreshTokenCookieFactory,
                redirectProperties,
                failureHandler
            );
    }

    @Test
    @DisplayName("OAuth 인증 성공 시 Refresh Token Cookie를 발급하고 Callback으로 이동한다")
    void authenticationSuccess_issuesRefreshTokenCookie()
        throws Exception {
        UUID userId = UUID.randomUUID();
        String rawRefreshToken =
            "family-id.refresh-token-secret";

        IssuedRefreshToken issuedRefreshToken =
            new IssuedRefreshToken(
                rawRefreshToken,
                Instant.now().plus(Duration.ofDays(7))
            );

        ResponseCookie responseCookie =
            ResponseCookie
                .from(
                    "REFRESH_TOKEN",
                    rawRefreshToken
                )
                .httpOnly(true)
                .path("/api/auth")
                .maxAge(Duration.ofDays(7))
                .build();

        when(authentication.getPrincipal())
            .thenReturn(oAuth2User);

        when(oAuth2User.getUserId())
            .thenReturn(userId);

        when(refreshTokenService.issue(userId))
            .thenReturn(issuedRefreshToken);

        when(
            refreshTokenCookieFactory.create(
                rawRefreshToken
            )
        ).thenReturn(responseCookie);

        when(redirectProperties.getSuccessUri())
            .thenReturn(
                URI.create(
                    "http://localhost:5173/oauth/callback"
                )
            );

        successHandler.onAuthenticationSuccess(
            request,
            response,
            authentication
        );

        verify(refreshTokenService).issue(userId);

        verify(refreshTokenCookieFactory)
            .create(rawRefreshToken);

        verify(response).addHeader(
            HttpHeaders.SET_COOKIE,
            responseCookie.toString()
        );

        /*
         * Access Token이나 Refresh Token을 URL에 포함하지 않고
         * 설정된 Callback URI만 사용했는지 확인한다.
         */
        verify(response).sendRedirect(
            "http://localhost:5173/oauth/callback"
        );

        verify(
            failureHandler,
            never()
        ).onAuthenticationFailure(
            any(),
            any(),
            any()
        );
    }

    @Test
    @DisplayName("OAuth Principal이 공통 타입이 아니면 토큰을 발급하지 않는다")
    void authenticationSuccess_failsWhenPrincipalIsUnsupported()
        throws Exception {
        when(authentication.getPrincipal())
            .thenReturn("unsupported-principal");

        successHandler.onAuthenticationSuccess(
            request,
            response,
            authentication
        );

        verify(failureHandler)
            .onAuthenticationFailure(
                eq(request),
                eq(response),
                any(AuthenticationServiceException.class)
            );

        verify(
            refreshTokenService,
            never()
        ).issue(any());

        verify(
            refreshTokenCookieFactory,
            never()
        ).create(any());

        verify(
            response,
            never()
        ).addHeader(
            any(),
            any()
        );

        verify(
            response,
            never()
        ).sendRedirect(any());
    }

    @Test
    @DisplayName("Refresh Token 발급에 실패하면 성공 Cookie와 Redirect를 반환하지 않는다")
    void authenticationSuccess_failsWhenRefreshTokenIssueFails()
        throws Exception {
        UUID userId = UUID.randomUUID();

        when(authentication.getPrincipal())
            .thenReturn(oAuth2User);

        when(oAuth2User.getUserId())
            .thenReturn(userId);

        when(refreshTokenService.issue(userId))
            .thenThrow(
                new IllegalStateException(
                    "Redis 저장 실패"
                )
            );

        successHandler.onAuthenticationSuccess(
            request,
            response,
            authentication
        );

        verify(failureHandler)
            .onAuthenticationFailure(
                eq(request),
                eq(response),
                any(AuthenticationServiceException.class)
            );

        verify(
            refreshTokenCookieFactory,
            never()
        ).create(any());

        verify(
            response,
            never()
        ).addHeader(
            any(),
            any()
        );

        verify(
            response,
            never()
        ).sendRedirect(any());
    }

    @Test
    @DisplayName("Refresh Token Cookie 생성에 실패하면 성공 Redirect를 반환하지 않는다")
    void authenticationSuccess_failsWhenCookieCreationFails()
        throws Exception {
        UUID userId = UUID.randomUUID();
        String rawRefreshToken =
            "family-id.refresh-token-secret";

        IssuedRefreshToken issuedRefreshToken =
            new IssuedRefreshToken(
                rawRefreshToken,
                Instant.now().plus(Duration.ofDays(7))
            );

        when(authentication.getPrincipal())
            .thenReturn(oAuth2User);

        when(oAuth2User.getUserId())
            .thenReturn(userId);

        when(refreshTokenService.issue(userId))
            .thenReturn(issuedRefreshToken);

        when(
            refreshTokenCookieFactory.create(
                rawRefreshToken
            )
        ).thenThrow(
            new IllegalArgumentException(
                "Cookie 생성 실패"
            )
        );

        successHandler.onAuthenticationSuccess(
            request,
            response,
            authentication
        );

        verify(failureHandler)
            .onAuthenticationFailure(
                eq(request),
                eq(response),
                any(AuthenticationServiceException.class)
            );

        verify(
            response,
            never()
        ).addHeader(
            any(),
            any()
        );

        verify(
            response,
            never()
        ).sendRedirect(any());
    }
}
