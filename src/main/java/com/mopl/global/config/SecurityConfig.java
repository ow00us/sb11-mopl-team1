package com.mopl.global.config;

import com.mopl.global.security.JwtAuthenticationFilter;
import com.mopl.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * 아직 남은 일(빌드 주차):
 *  1. JwtProviderImpl의 발급/검증 실구현.
 *  2. 지금은 개발을 막지 않으려고 anyRequest를 permitAll로 열어 두었습니다.
 *     JWT 구현이 끝나면 아래 anyRequest를 .authenticated()로 바꿔 실제로 잠급니다.
 *  3. CSRF가 실제로 동작하려면 GET /api/auth/csrf-token 처럼 토큰을 발급·노출하는 흐름이 필요합니다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;

    /** 인증 없이 접근 가능한 공개 경로입니다. */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/**",
            "/api/auth/csrf-token",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // TODO(빌드 주차): JWT 구현 완료 후 아래를 .authenticated() 로 전환합니다.
                        .anyRequest().permitAll())
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
