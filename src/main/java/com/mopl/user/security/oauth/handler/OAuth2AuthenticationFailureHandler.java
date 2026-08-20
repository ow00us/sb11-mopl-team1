package com.mopl.user.security.oauth.handler;

import com.mopl.user.config.OAuthRedirectProperties;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth2 인증 실패 후 프론트엔드 로그인 화면으로 Redirect하는 Handler
 *
 * <p>Provider가 전달한 오류 메시지나 예외 상세 내용은 URL에 포함하지 않고,
 * 프론트엔드가 처리할 수 있는 고정 오류 코드만 전달합니다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationFailureHandler
    implements AuthenticationFailureHandler {

    public static final String FAILURE_ERROR_CODE =
        "oauth_authentication_failed";

    private final OAuthRedirectProperties redirectProperties;

    /**
     * OAuth2 인증 실패 결과를 처리
     *
     * <p>성공 Handler에서 Refresh Token 발급에 실패해 이 Handler를
     * 직접 호출하는 경우도 있으므로 SecurityContext를 명시적으로
     * 초기화합니다.</p>
     */
    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException exception
    ) throws IOException, ServletException {
        SecurityContextHolder.clearContext();

        /*
         * 사용자 이메일, Provider Access Token 및 예외 메시지는
         * 로그에 남기지 않고 예외 타입만 기록
         */
        log.warn(
            "OAuth2 authentication failed: {}",
            exception.getClass().getSimpleName()
        );

        URI failureRedirectUri =
            UriComponentsBuilder
                .fromUri(redirectProperties.getFailureUri())
                .queryParam(
                    "error",
                    FAILURE_ERROR_CODE
                )
                .build()
                .encode()
                .toUri();

        response.sendRedirect(
            failureRedirectUri.toASCIIString()
        );
    }
}
