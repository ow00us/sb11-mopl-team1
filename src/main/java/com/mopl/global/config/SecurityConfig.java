package com.mopl.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.security.JwtAuthenticationFilter;
import com.mopl.global.security.JwtProvider;
import com.mopl.global.security.csrf.RotatingCookieCsrfTokenRepository;
import com.mopl.global.security.handler.RestAccessDeniedHandler;
import com.mopl.global.security.handler.RestAuthenticationEntryPoint;
import com.mopl.global.security.handler.SecurityErrorResponseWriter;
import com.mopl.user.security.oauth.GoogleOidcUserService;
import com.mopl.user.security.oauth.handler.OAuth2AuthenticationFailureHandler;
import com.mopl.user.security.oauth.handler.OAuth2AuthenticationSuccessHandler;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 보안 설정 골격입니다. 다음을 자리 잡아 두었습니다.
 *  - JWT 인증 필터를 스프링 시큐리티 체인에 연결
 *  - CSRF를 쿠키 방식으로 설정(쿠키 XSRF-TOKEN / 헤더 X-XSRF-TOKEN — 요구사항 준수)
 *  - JWT SecurityContext는 HTTP 세션에 저장하지 않고 STATELESS로 관리
 *  - OAuth2 인가 요청 정보는 Provider 연동 과정에서 임시 HTTP 세션을 사용할 수 있음
 *
 * 공개 API는 회원가입과 인증 진입점으로 한정합니다.
 * 공개 상태 변경 요청도 CSRF 검증은 그대로 적용합니다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;

    @Value("${app.cors.allowed-origins}")
    private String[] allowedCorsOrigins;

    /** JWT 인증 없이 접근 가능한 공개 GET 경로입니다. */
    private static final String[] PUBLIC_GET_PATHS = {
            "/api/auth/csrf-token",
            "/oauth2/authorization/**",
            "/login/oauth2/code/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    /** JWT 인증 없이 접근 가능한 공개 POST 경로입니다. */
    private static final String[] PUBLIC_POST_PATHS = {
            "/api/users",
            "/api/auth/sign-in",
            "/api/auth/reset-password",
            "/api/auth/refresh"
    };

    /** 별도 프로토콜에서 인증하는 공개 handshake 경로입니다. */
    private static final String[] PUBLIC_HANDSHAKE_PATHS = {
            "/ws/**"
    };

    static boolean isPublicPostPath(String path) {
        return Arrays.asList(PUBLIC_POST_PATHS).contains(path);
    }

    static boolean isPublicGetPath(String path) {
        return Arrays.asList(PUBLIC_GET_PATHS).contains(path);
    }

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
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedCorsOrigins));
        configuration.setAllowedMethods(List.of(
            HttpMethod.GET.name(),
            HttpMethod.POST.name(),
            HttpMethod.PUT.name(),
            HttpMethod.PATCH.name(),
            HttpMethod.DELETE.name(),
            HttpMethod.OPTIONS.name()
        ));
        configuration.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type",
            "X-XSRF-TOKEN"
        ));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(
        HttpSecurity http,
        RestAuthenticationEntryPoint authenticationEntryPoint,
        RestAccessDeniedHandler accessDeniedHandler,
        CorsConfigurationSource corsConfigurationSource,
        ObjectProvider<ClientRegistrationRepository>
            clientRegistrationRepositoryProvider,
        ObjectProvider<OAuth2AuthenticationSuccessHandler>
            successHandlerProvider,
        ObjectProvider<OAuth2AuthenticationFailureHandler>
            failureHandlerProvider,
        ObjectProvider<GoogleOidcUserService>
            googleOidcUserServiceProvider
    ) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(RotatingCookieCsrfTokenRepository.withHttpOnlyFalse())
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
                        .requestMatchers(HttpMethod.GET, "/api/users").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/users/*/locked", "/api/users/*/role").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);

        /*
         * Provider별 ClientRegistration이 등록된 경우에만
         * OAuth2 Login 필터를 SecurityFilterChain에 연결
         *
         * 현재 공통 기반 PR에는 Google, Kakao, Naver의 실제 Client ID와
         * Client Secret이 아직 없으므로 무조건 oauth2Login()을 적용하면
         * ClientRegistrationRepository가 생성되지 않은 테스트와 로컬 환경에서
         * ApplicationContext 시작이 실패할 수 있다.
         */
        ClientRegistrationRepository clientRegistrationRepository =
            clientRegistrationRepositoryProvider.getIfAvailable();

        OAuth2AuthenticationSuccessHandler successHandler =
            successHandlerProvider.getIfAvailable();

        OAuth2AuthenticationFailureHandler failureHandler =
            failureHandlerProvider.getIfAvailable();

        GoogleOidcUserService googleOidcUserService =
            googleOidcUserServiceProvider.getIfAvailable();

        if (clientRegistrationRepository != null
            && successHandler != null
            && failureHandler != null) {
            http.oauth2Login(oauth2 -> {
                oauth2
                    .successHandler(successHandler)
                    .failureHandler(failureHandler);

                /*
                 * Google은 openid scope를 사용하는 OIDC Provider이므로
                 * 일반 OAuth2UserService가 아닌 전용 OIDC 사용자 서비스를 연결
                 */
                if (googleOidcUserService != null) {
                    oauth2.userInfoEndpoint(userInfo ->
                        userInfo.oidcUserService(
                            googleOidcUserService
                        )
                    );
                }
            });
        }

        return http.build();
    }
}
