package com.mopl.user.security.oauth.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.user.config.OAuthRedirectProperties;
import com.mopl.user.security.oauth.link.OAuthLinkIntentSessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

@ExtendWith({
    MockitoExtension.class,
    OutputCaptureExtension.class
})
class OAuth2AuthenticationFailureHandlerTest {

    @Mock
    OAuthRedirectProperties redirectProperties;

    @Mock
    OAuthLinkIntentSessionStore linkIntentSessionStore;

    @Mock
    HttpServletRequest request;

    @Mock
    HttpServletResponse response;

    OAuth2AuthenticationFailureHandler failureHandler;

    @BeforeEach
    void setUp() {
        failureHandler =
            new OAuth2AuthenticationFailureHandler(
                redirectProperties,
                linkIntentSessionStore
            );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("OAuth 인증 실패 시 고정 오류 코드와 함께 로그인 화면으로 이동한다")
    void authenticationFailure_redirectsWithFixedErrorCode()
        throws Exception {
        when(redirectProperties.getFailureUri())
            .thenReturn(
                URI.create(
                    "http://localhost:5173/sign-in"
                )
            );

        AuthenticationServiceException exception =
            new AuthenticationServiceException(
                "Provider Access Token과 사용자 정보가 포함된 내부 오류"
            );

        failureHandler.onAuthenticationFailure(
            request,
            response,
            exception
        );

        verify(response).sendRedirect(
            "http://localhost:5173/sign-in"
                + "?error=oauth_authentication_failed"
        );
    }

    @Test
    @DisplayName("OAuth 인증 실패 시 세션에 남은 계정 연결 의도를 제거한다")
    void authenticationFailure_clearsLinkIntent()
        throws Exception {
        // given
        when(redirectProperties.getFailureUri())
            .thenReturn(
                URI.create(
                    "http://localhost:5173/sign-in"
                )
            );

        // when
        failureHandler.onAuthenticationFailure(
            request,
            response,
            new AuthenticationServiceException(
                "OAuth 인증 실패"
            )
        );

        // then
        verify(linkIntentSessionStore)
            .clear(request);

        verify(response)
            .sendRedirect(
                "http://localhost:5173/sign-in"
                    + "?error=oauth_authentication_failed"
            );
    }

    @Test
    @DisplayName("OAuth 인증 실패 시 기존 SecurityContext를 제거한다")
    void authenticationFailure_clearsSecurityContext()
        throws Exception {
        when(redirectProperties.getFailureUri())
            .thenReturn(
                URI.create(
                    "http://localhost:5173/sign-in"
                )
            );

        Authentication authentication =
            mock(Authentication.class);

        SecurityContextHolder
            .getContext()
            .setAuthentication(authentication);

        failureHandler.onAuthenticationFailure(
            request,
            response,
            new AuthenticationServiceException(
                "OAuth 인증 실패"
            )
        );

        assertThat(
            SecurityContextHolder
                .getContext()
                .getAuthentication()
        ).isNull();
    }

    @Test
    @DisplayName("OAuth 인증 실패 로그에는 오류 코드만 남기고 상세 메시지는 노출하지 않는다")
    void authenticationFailure_logsSafeOAuthErrorCode(
        CapturedOutput output
    ) throws Exception {
        when(redirectProperties.getFailureUri())
            .thenReturn(
                URI.create(
                    "http://localhost:5173/sign-in"
                )
            );

        OAuth2AuthenticationException exception =
            new OAuth2AuthenticationException(
                new OAuth2Error(
                    "invalid_token_response",
                    "Provider Access Token과 사용자 정보가 포함된 내부 오류",
                    null
                )
            );

        failureHandler.onAuthenticationFailure(
            request,
            response,
            exception
        );

        assertThat(output)
            .contains(
                "type=OAuth2AuthenticationException"
            )
            .contains(
                "errorCode=invalid_token_response"
            )
            .doesNotContain(
                "Provider Access Token과 사용자 정보가 포함된 내부 오류"
            );
    }
}
