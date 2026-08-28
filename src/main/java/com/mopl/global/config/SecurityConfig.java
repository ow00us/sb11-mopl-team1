package com.mopl.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.security.JwtAuthenticationFilter;
import com.mopl.global.security.JwtProvider;
import com.mopl.global.security.csrf.RotatingCookieCsrfTokenRepository;
import com.mopl.global.security.handler.RestAccessDeniedHandler;
import com.mopl.global.security.handler.RestAuthenticationEntryPoint;
import com.mopl.global.security.handler.SecurityErrorResponseWriter;
import com.mopl.user.service.AccessTokenUserStatusService;
import com.mopl.user.security.oauth.GoogleOidcUserService;
import com.mopl.user.security.oauth.MoplOAuth2UserService;
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
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 보안 설정 골격입니다. 다음을 자리 잡아 두었습니다.
 *  - JWT 인증 필터를 스프링 시큐리티 체인에 연결
 *  - CSRF를 쿠키 방식으로 설정(쿠키 XSRF-TOKEN / 헤더 X-XSRF-TOKEN — 요구사항 준수)
 *  - JWT SecurityContext는 HTTP 세션에 저장하지 않고 STATELESS로 관리
 *  - OAuth2 인가 요청은 HTTP 세션이 아니라 Redis 에 두어 인스턴스 사이에서 공유
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
            "/actuator/health",
            // liveness·readiness probe 경로입니다. 오케스트레이터가 인증 없이 호출합니다.
            // 상세는 management.endpoint.health.show-details 가 가립니다.
            "/actuator/health/**"
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
        AccessTokenUserStatusService accessTokenUserStatusService,
        SecurityErrorResponseWriter responseWriter,
        ObjectProvider<ClientRegistrationRepository>
            clientRegistrationRepositoryProvider,
        ObjectProvider<OAuth2AuthenticationSuccessHandler>
            successHandlerProvider,
        ObjectProvider<OAuth2AuthenticationFailureHandler>
            failureHandlerProvider,
        ObjectProvider<GoogleOidcUserService>
            googleOidcUserServiceProvider,
        ObjectProvider<MoplOAuth2UserService>
            moplOAuth2UserServiceProvider,
        ObjectProvider<AuthorizationRequestRepository<OAuth2AuthorizationRequest>>
            authorizationRequestRepositoryProvider
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
                        // 운영 경계는 경로 하나로 묶어 둡니다. 새 관리자 API 를 추가할 때
                        // 권한 부여를 따로 기억해야 하면 언젠가 빠뜨립니다.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, accessTokenUserStatusService, responseWriter), UsernamePasswordAuthenticationFilter.class);

        /*
         * OAuth ClientRegistration과 공통 성공·실패 Handler가
         * 모두 등록된 환경에서만 OAuth2 Login 필터를 연결한다.
         *
         * ObjectProvider를 사용하면 OAuth 설정을 로드하지 않는 슬라이스 테스트나
         * 일부 제한된 실행 환경에서도 SecurityFilterChain을 구성할 수 있다.
         */
        ClientRegistrationRepository clientRegistrationRepository =
            clientRegistrationRepositoryProvider.getIfAvailable();

        OAuth2AuthenticationSuccessHandler successHandler =
            successHandlerProvider.getIfAvailable();

        OAuth2AuthenticationFailureHandler failureHandler =
            failureHandlerProvider.getIfAvailable();

        GoogleOidcUserService googleOidcUserService =
            googleOidcUserServiceProvider.getIfAvailable();

        MoplOAuth2UserService moplOAuth2UserService =
            moplOAuth2UserServiceProvider.getIfAvailable();

        if (clientRegistrationRepository != null
            && successHandler != null
            && failureHandler != null) {
            http.oauth2Login(oauth2 -> {
                oauth2
                    .successHandler(successHandler)
                    .failureHandler(failureHandler);

                /*
                 * 인가 요청 저장소를 공유 저장소로 교체
                 *
                 * 기본 구현은 인가 요청을 HTTP 세션에 두는데 세션은 인스턴스 로컬이다.
                 * 백엔드를 두 개 띄우면 인가를 시작한 인스턴스와 Provider가 callback을
                 * 보낸 인스턴스가 달라져 로그인이 절반 확률로 실패한다.
                 */
                AuthorizationRequestRepository<OAuth2AuthorizationRequest>
                    authorizationRequestRepository =
                        authorizationRequestRepositoryProvider.getIfAvailable();

                if (authorizationRequestRepository != null) {
                    oauth2.authorizationEndpoint(authorization ->
                        authorization.authorizationRequestRepository(
                            authorizationRequestRepository
                        )
                    );
                }

                /*
                 * Google은 openid scope를 사용하는 OIDC Provider이므로
                 * oidcUserService에 연결
                 *
                 * Kakao와 Naver 같은 일반 OAuth2 Provider는
                 * registrationId를 기준으로 분기하는 공통 라우터에 연결
                 */
                oauth2.userInfoEndpoint(userInfo -> {
                    if (googleOidcUserService != null) {
                        userInfo.oidcUserService(
                            googleOidcUserService
                        );
                    }

                    if (moplOAuth2UserService != null) {
                        userInfo.userService(
                            moplOAuth2UserService
                        );
                    }
                });
            });
        }

        return http.build();
    }
}
