package com.mopl.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.security.JwtAuthenticationFilter;
import com.mopl.global.security.JwtProvider;
import com.mopl.global.security.handler.RestAccessDeniedHandler;
import com.mopl.global.security.handler.RestAuthenticationEntryPoint;
import com.mopl.global.security.handler.SecurityErrorResponseWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 보안 설정 골격입니다. 다음을 자리 잡아 두었습니다.
 *  - JWT 인증 필터를 스프링 시큐리티 체인에 연결
 *  - CSRF를 쿠키 방식으로 설정(쿠키 XSRF-TOKEN / 헤더 X-XSRF-TOKEN — 요구사항 준수)
 *  - 세션은 STATELESS(서버 세션을 만들지 않음)
 *
 * 공개 API는 회원가입과 인증 진입점으로 한정합니다.
 * 공개 상태 변경 요청도 CSRF 검증은 그대로 적용합니다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;

    /** JWT 인증 없이 접근 가능한 공개 GET 경로입니다. */
    private static final String[] PUBLIC_GET_PATHS = {
            "/api/auth/csrf-token",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    /** JWT 인증 없이 접근 가능한 공개 POST 경로입니다. */
    private static final String[] PUBLIC_POST_PATHS = {
            "/api/users",
            "/api/auth/sign-in"
    };

    /** 별도 프로토콜에서 인증하는 공개 handshake 경로입니다. */
    private static final String[] PUBLIC_HANDSHAKE_PATHS = {
            "/ws/**"
    };

    @Bean
    public SecurityErrorResponseWriter securityErrorResponseWriter(
        ObjectMapper objectMapper
    ) {
        return new SecurityErrorResponseWriter(objectMapper);
    }

    @Bean
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(
        SecurityErrorResponseWriter responseWriter
    ) {
        return new RestAuthenticationEntryPoint(responseWriter);
    }

    @Bean
    public RestAccessDeniedHandler restAccessDeniedHandler(
        SecurityErrorResponseWriter responseWriter
    ) {
        return new RestAccessDeniedHandler(responseWriter);
    }

    @Bean
    public SecurityFilterChain filterChain(
        HttpSecurity http,
        RestAuthenticationEntryPoint authenticationEntryPoint,
        RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/ws/**"))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
                        .requestMatchers(PUBLIC_HANDSHAKE_PATHS).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
