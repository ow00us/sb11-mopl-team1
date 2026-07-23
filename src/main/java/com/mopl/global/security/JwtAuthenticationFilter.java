package com.mopl.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 요청마다 한 번 실행되며, Authorization 헤더의 Bearer 토큰을 꺼내 검증합니다.
 * 유효한 토큰이면 SecurityContext에 인증 정보를 심어 이후 인가 판단에 사용합니다.
 * (지금은 JwtProviderImpl.validate가 false라 아무 인증도 세팅하지 않습니다 — 구현 후 실제로 동작합니다.)
 *
 * 일부러 @Component로 만들지 않습니다. 스프링 빈으로 두면 (1) 서블릿 필터로도 자동 등록되어 Security 체인과 중복 실행되고,
 * (2) @WebMvcTest 슬라이스가 이 필터만 로딩하면서 JwtProvider 빈을 못 찾아 실패합니다.
 * 대신 SecurityConfig가 JwtProvider를 받아 직접 생성해 체인에만 등록합니다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && jwtProvider.validate(token)) {
            Authentication authentication = jwtProvider.getAuthentication(token);
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
