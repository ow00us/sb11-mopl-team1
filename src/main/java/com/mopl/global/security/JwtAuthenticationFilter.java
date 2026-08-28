package com.mopl.global.security;

import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.handler.SecurityErrorResponseWriter;
import com.mopl.user.service.AccessTokenAuthenticationStatus;
import com.mopl.user.service.AccessTokenUserStatusService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청마다 한 번 실행되며, Authorization 헤더의 Bearer 토큰을 꺼내 검증합니다.
 * 유효한 토큰이면 SecurityContext에 인증 정보를 심어 이후 인가 판단에 사용합니다.
 *
 * <p>JWT가 유효하더라도 탈퇴하거나 잠긴 사용자는 인증하지 않습니다.
 * Redis와 데이터베이스가 모두 실패하면 보안을 우선하여 요청을
 * 503으로 종료합니다.</p>
 *
 * 일부러 @Component로 만들지 않습니다. 스프링 빈으로 두면 (1) 서블릿 필터로도 자동 등록되어 Security 체인과 중복 실행되고,
 * (2) @WebMvcTest 슬라이스가 이 필터만 로딩하면서 JwtProvider 빈을 못 찾아 실패합니다.
 * 대신 SecurityConfig가 JwtProvider를 받아 직접 생성해 체인에만 등록합니다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String
        ACCESS_TOKEN_AUTHENTICATION_EXCEPTION =
        "AccessTokenAuthenticationException";

    private static final String
        AUTHENTICATION_SERVICE_UNAVAILABLE_EXCEPTION =
        "AuthenticationServiceUnavailableException";

    private final JwtProvider jwtProvider;

    private final AccessTokenUserStatusService accessTokenUserStatusService;

    private final SecurityErrorResponseWriter responseWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && jwtProvider.validate(token)) {
            Authentication authentication = jwtProvider.getAuthentication(token);

            UUID userId = (UUID) authentication.getPrincipal();

            AccessTokenAuthenticationStatus status =
                accessTokenUserStatusService.resolve(userId);

            if (
                status
                    != AccessTokenAuthenticationStatus.ALLOWED
                    && status != AccessTokenAuthenticationStatus.UNAVAILABLE
            ) {
                SecurityContextHolder.clearContext();

                responseWriter.write(
                    response,
                    ACCESS_TOKEN_AUTHENTICATION_EXCEPTION,
                    ErrorCode.UNAUTHORIZED
                );
                return;
            }

            if (status == AccessTokenAuthenticationStatus.UNAVAILABLE) {
                SecurityContextHolder.clearContext();

                responseWriter.write(
                    response,
                    AUTHENTICATION_SERVICE_UNAVAILABLE_EXCEPTION,
                    ErrorCode.SERVICE_UNAVAILABLE
                );
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
