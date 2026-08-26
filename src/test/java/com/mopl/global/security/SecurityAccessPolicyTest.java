package com.mopl.global.security;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.config.SecurityConfig;
import com.mopl.global.security.controller.CsrfTokenController;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

@WebMvcTest(SecurityPolicyProbeController.class)
@ActiveProfiles({"test", "security-policy-test"})
@Import({
    SecurityConfig.class,
    CsrfTokenController.class
})
class SecurityAccessPolicyTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtProvider jwtProvider;

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/users",
        "/api/auth/sign-in",
        "/api/auth/reset-password",
        "/api/auth/refresh"
    })
    @DisplayName("공개 POST API는 CSRF 토큰이 있으면 JWT 없이 접근할 수 있다")
    void publicPost_withCsrf_doesNotRequireJwt(String path) throws Exception {
        mockMvc.perform(
                post(path)
                    .with(csrf())
            )
            .andExpect(
                status().isNoContent()
            );

        /*
         * 공개 API이므로 Authorization 헤더가 없는 요청에 대해
         * JWT 검증을 시도하지 않아야 합니다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers
                .anyString()
        );
    }

    @Test
    @DisplayName("공개 POST API도 CSRF 토큰이 없으면 403을 반환한다")
    void publicPost_withoutCsrf_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/users"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    @Test
    @DisplayName("비밀번호 초기화 API는 CSRF 토큰이 없으면 403을 반환한다")
    void resetPassword_withoutCsrf_returnsForbidden()
        throws Exception {

        /*
         * 비밀번호 초기화는 Access Token 없이 접근 가능한 공개 API이지만,
         * 사용자의 비밀번호 상태를 변경하는 POST 요청
         *
         * 따라서 공격 사이트에서 사용자의 브라우저를 통해 임의로
         * 비밀번호 초기화를 요청하지 못하도록 CSRF 검증을 적용
         */
        mockMvc.perform(
                post(
                    "/api/auth/reset-password"
                )
            )
            .andExpect(
                status().isForbidden()
            )
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_403_1")
            );

        /*
         * 403은 JWT 인증 실패가 아니라 CSRF 검증 실패로 발생해야 한다.
         *
         * reset-password는 공개 경로이므로 JwtProvider가 Access Token을
         * 검증하려고 호출되어서는 안된다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers
                .anyString()
        );
    }

    @Test
    @DisplayName("토큰 재발급 API는 CSRF 토큰이 없으면 403을 반환한다")
    void refresh_withoutCsrf_returnsForbidden()
        throws Exception {

        /*
         * /api/auth/refresh는 Access Token 인증이 필요 없는 공개 경로지만
         * Refresh Token Cookie를 사용하는 상태 변경 POST 요청이므로
         * CSRF 검증 대상
         *
         * SecurityPolicyProbeController를 사용하므로 실제 Refresh Token
         * Cookie 바인딩이나 재발급 Service 로직에는 진입하지 않는다.
         */
        mockMvc.perform(
                post("/api/auth/refresh")
            )
            .andExpect(status().isForbidden())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_403_1")
            );

        /*
         * 이번 403은 JWT 인증 실패가 아니라 CSRF 검증 실패로
         * 발생해야 하므로 JwtProvider는 호출되지 않는다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("토큰 재발급 응답에는 인증 정보 캐시 방지 헤더를 포함한다")
    void refresh_withCsrf_returnsNoStoreHeaders()
        throws Exception {

        /*
         * 토큰 재발급 API는 Access Token과 Refresh Token을 새로 발급하는
         * 인증 API이므로 응답이 브라우저나 중간 캐시에 저장되면 안 된다.
         *
         * SecurityConfig에서 Spring Security 기본 보안 헤더를
         * 비활성화하지 않았으므로 HeaderWriterFilter가
         * 캐시 방지 응답 헤더를 추가
         */
        mockMvc.perform(
                post("/api/auth/refresh")
                    .with(csrf())
            )
            .andExpect(status().isNoContent())
            .andExpect(
                header().string(
                    HttpHeaders.CACHE_CONTROL,
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(
                            "no-cache"
                        ),
                        org.hamcrest.Matchers.containsString(
                            "no-store"
                        )
                    )
                )
            )
            .andExpect(
                header().string(
                    "Pragma",
                    "no-cache"
                )
            );

        /*
         * 재발급 API는 JWT 없이 접근 가능한 공개 POST 경로이므로
         * Access Token 검증은 실행되지 않아야 한다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("CSRF 토큰 발급 API는 JWT 없이 접근할 수 있다")
    void csrfTokenEndpoint_doesNotRequireJwt() throws Exception {
        mockMvc.perform(get("/api/auth/csrf-token"))
            .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/oauth2/authorization/google",
        "/login/oauth2/code/google",
        "/oauth2/authorization/kakao",
        "/login/oauth2/code/kakao",
        "/oauth2/authorization/naver",
        "/login/oauth2/code/naver"
    })
    @DisplayName("OAuth2 인증 진입점과 Callback 경로는 JWT 없이 접근할 수 있다")
    void oauth2Paths_withoutJwt_passSecurityFilter(
        String path
    ) throws Exception {
        /*
         * 현재 테스트 환경에는 실제 OAuth ClientRegistration이 없으므로
         * OAuth2 Login Filter는 활성화되지 않는다.
         *
         * 따라서 공개 보안 경로를 통과한 뒤 요청을 처리할 Controller가 없어
         * 404가 반환되는 것이 정상이다.
         * 이 테스트의 목적은 해당 요청이
         * JWT 부재로 401 또는 403을 반환하지 않는지 확인하는 것이다.
         */
        mockMvc.perform(
                get(path)
            )
            .andExpect(
                status().isNotFound()
            );

        /*
         * Authorization 헤더가 없고 OAuth2 공개 경로이므로
         * JWT Provider가 Access Token 검증을 시도해서는 안된.
         */
        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("WebSocket handshake 경로는 HTTP 메서드와 CSRF에 관계없이 접근할 수 있다")
    void webSocketHandshake_doesNotRequireJwtOrCsrf() throws Exception {
        mockMvc.perform(post("/ws/security-policy"))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("허용된 origin의 preflight 요청에는 CORS 응답 헤더를 반환한다")
    void preflight_withAllowedOrigin_returnsCorsHeaders() throws Exception {
        mockMvc.perform(options("/api/security-policy/protected")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(
                    HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                    HttpMethod.GET.name()
                )
                .header(
                    HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                    "Authorization,X-XSRF-TOKEN"
                ))
            .andExpect(status().isOk())
            .andExpect(header().string(
                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                ALLOWED_ORIGIN
            ))
            .andExpect(header().string(
                HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                "true"
            ))
            .andExpect(header().string(
                HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                org.hamcrest.Matchers.containsString(HttpMethod.GET.name())
            ))
            .andExpect(header().string(
                HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                org.hamcrest.Matchers.containsString("Authorization")
            ))
            .andExpect(header().string(
                HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                org.hamcrest.Matchers.containsString("X-XSRF-TOKEN")
            ));
    }

    @Test
    @DisplayName("허용되지 않은 origin의 preflight 요청은 거부한다")
    void preflight_withDisallowedOrigin_returnsForbidden() throws Exception {
        mockMvc.perform(options("/api/security-policy/protected")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(
                    HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                    HttpMethod.GET.name()
                ))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(
                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN
            ));
    }

    @Test
    @DisplayName("보호 API는 토큰이 없으면 401을 반환한다")
    void protectedApi_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/security-policy/protected"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("COMMON_401_1"));
    }

    /**
     * 관리자 사용자 목록 조회 API는 인증되지 않은 사용자가
     * 접근할 수 없는 보호 API인지 검증
     */
    @Test
    @DisplayName("인증되지 않은 사용자가 사용자 목록을 조회하면 401을 반환한다")
    void findUsers_unauthenticated_returnsUnauthorized() throws Exception {
        // when & then
        /*
         * Authorization 헤더를 전달하지 않으므로
         * SecurityContext에는 인증 정보가 존재하지 않는다.
         *
         * GET 요청은 CSRF 검증 대상이 아니므로 별도의 csrf()는 필요하지 않다.
         */
        mockMvc.perform(
                get("/api/users")
                    .param("limit", "20")
                    .param("sortDirection", "ASCENDING")
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        /*
         * Bearer 토큰 자체가 전달되지 않았으므로
         * JwtProvider의 토큰 검증도 실행되지 않아야 한다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * JWT 인증에는 성공했지만 ROLE_USER 권한만 가진 사용자가
     * 관리자 사용자 목록 조회 API에 접근하면 차단되는지 검증
     */
    @Test
    @DisplayName("일반 사용자가 사용자 목록을 조회하면 403을 반환한다")
    void findUsers_user_returnsForbidden() throws Exception {
        // given
        /*
         * 유효한 JWT로 인증됐지만 관리자 권한은 없는 사용자를 구성
         */
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.fromString(USER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

        when(jwtProvider.validate("user-token"))
            .thenReturn(true);

        when(jwtProvider.getAuthentication("user-token"))
            .thenReturn(authentication);

        // when & then
        mockMvc.perform(
                get("/api/users")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer user-token"
                    )
                    .param("limit", "20")
                    .param("sortDirection", "ASCENDING")
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isForbidden())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_403_1")
            );

        /*
         * JWT 검증과 Authentication 생성에는 성공했는지 확인
         *
         * 이를 통해 이번 403이 인증 실패가 아니라
         * ROLE_ADMIN 권한 부족으로 발생했음을 보장
         */
        verify(jwtProvider).validate("user-token");
        verify(jwtProvider).getAuthentication("user-token");
    }

    /**
     * ROLE_ADMIN 권한을 가진 사용자는 보안 필터를 통과해
     * 사용자 목록 조회 Controller에 도달하는지 검증
     */
    @Test
    @DisplayName("관리자가 사용자 목록을 조회하면 보안 필터를 통과한다")
    void findUsers_admin_passesSecurityFilter() throws Exception {
        // given
        /*
         * ROLE_ADMIN 권한을 가진 인증 객체를 구성
         */
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.fromString(USER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );

        when(jwtProvider.validate("admin-token"))
            .thenReturn(true);

        when(jwtProvider.getAuthentication("admin-token"))
            .thenReturn(authentication);

        // when & then
        /*
         * SecurityPolicyProbeController의 테스트 전용 메서드는
         * 요청이 보안 필터를 통과하면 204 No Content를 반환
         */
        mockMvc.perform(
                get("/api/users")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer admin-token"
                    )
                    .param("limit", "20")
                    .param("sortDirection", "ASCENDING")
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isNoContent());

        verify(jwtProvider).validate("admin-token");
        verify(jwtProvider).getAuthentication("admin-token");
    }

    @Test
    @DisplayName("보호 API는 유효하지 않은 Bearer 토큰이면 401을 반환한다")
    void protectedApi_withInvalidToken_returnsUnauthorized() throws Exception {
        when(jwtProvider.validate("invalid-token")).thenReturn(false);

        mockMvc.perform(get("/api/security-policy/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("COMMON_401_1"));

        verify(jwtProvider).validate("invalid-token");
        verify(jwtProvider, never()).getAuthentication("invalid-token");
    }

    @Test
    @DisplayName("보호 API는 유효한 Bearer 토큰의 인증 주체로 접근할 수 있다")
    void protectedApi_withValidToken_usesAuthentication() throws Exception {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            UUID.fromString(USER_ID),
            null,
            List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        when(jwtProvider.validate("valid-token")).thenReturn(true);
        when(jwtProvider.getAuthentication("valid-token")).thenReturn(authentication);

        mockMvc.perform(get("/api/security-policy/protected")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
            .andExpect(status().isNoContent());

        verify(jwtProvider).validate("valid-token");
        verify(jwtProvider).getAuthentication("valid-token");
    }

    @Test
    @DisplayName("로그아웃은 인증 경로에 속해도 JWT가 필요한 보호 API다")
    void signOut_withoutToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/sign-out").with(csrf()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("COMMON_401_1"));
    }

    @Test
    @DisplayName("로그아웃은 유효한 JWT와 CSRF 토큰이 있으면 접근할 수 있다")
    void signOut_withValidTokenAndCsrf_returnsNoContent()
        throws Exception {

        /*
         * 로그아웃 API는 공개 인증 API가 아니라
         * Access Token 인증이 필요한 보호 API
         *
         * JWT에서 복원한 사용자 UUID는 실제 AuthController에서
         * 현재 사용자의 Refresh Token 세션을 폐기할 때 사용
         */
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.fromString(USER_ID),
                null,
                List.of(
                    new SimpleGrantedAuthority("ROLE_USER")
                )
            );

        when(jwtProvider.validate("valid-token"))
            .thenReturn(true);
        when(jwtProvider.getAuthentication("valid-token"))
            .thenReturn(authentication);

        /*
         * POST /api/auth/sign-out은 상태를 변경하는 요청이므로
         * 유효한 Access Token뿐만 아니라 CSRF 토큰도 필요
         */
        mockMvc.perform(
                post("/api/auth/sign-out")
                    .with(csrf())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer valid-token"
                    )
            )
            .andExpect(status().isNoContent());

        /*
         * JWT 필터가 토큰을 검증한 뒤 Authentication을 생성했는지 확인
         */
        verify(jwtProvider).validate("valid-token");
        verify(jwtProvider)
            .getAuthentication("valid-token");
    }

    @Test
    @DisplayName("OAuth 계정 연결 시작 API는 인증되지 않은 요청을 401로 거부한다")
    void startOAuthAccountLink_withoutJwt_returnsUnauthorized()
        throws Exception {

        mockMvc.perform(
                post(
                    "/api/users/{userId}/oauth-accounts/{provider}/link",
                    USER_ID,
                    "GOOGLE"
                )
                    .with(csrf())
            )
            .andExpect(
                status().isUnauthorized()
            )
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("OAuth 계정 연결 시작 API는 CSRF 토큰이 없으면 403을 반환한다")
    void startOAuthAccountLink_withoutCsrf_returnsForbidden()
        throws Exception {

        mockMvc.perform(
                post(
                    "/api/users/{userId}/oauth-accounts/{provider}/link",
                    USER_ID,
                    "GOOGLE"
                )
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer valid-token"
                    )
            )
            .andExpect(
                status().isForbidden()
            )
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_403_1")
            );

        /*
         * CSRF 필터가 JWT 필터보다 먼저 요청을 차단해야 한다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("OAuth 계정 연결 시작 API는 유효한 JWT와 CSRF 토큰으로 접근할 수 있다")
    void startOAuthAccountLink_withJwtAndCsrf_passesSecurityFilter()
        throws Exception {

        var authentication =
            UsernamePasswordAuthenticationToken
                .authenticated(
                    UUID.fromString(USER_ID),
                    null,
                    List.of(
                        new SimpleGrantedAuthority(
                            "ROLE_USER"
                        )
                    )
                );

        when(jwtProvider.validate("valid-token"))
            .thenReturn(true);

        when(
            jwtProvider.getAuthentication(
                "valid-token"
            )
        ).thenReturn(authentication);

        mockMvc.perform(
                post(
                    "/api/users/{userId}/oauth-accounts/{provider}/link",
                    USER_ID,
                    "GOOGLE"
                )
                    .with(csrf())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer valid-token"
                    )
            )
            .andExpect(
                status().isNoContent()
            );

        verify(jwtProvider)
            .validate("valid-token");

        verify(jwtProvider)
            .getAuthentication("valid-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/users/11111111-1111-1111-1111-111111111111"
            + "/local-credentials/email-verifications",
        "/api/users/11111111-1111-1111-1111-111111111111"
            + "/local-credentials"
    })
    @DisplayName("로컬 로그인 등록 API는 인증되지 않은 잘못된 요청도 401로 거부한다")
    void localCredentialApi_withoutJwt_returnsUnauthorized(
        String path
    ) throws Exception {

        /*
         * 빈 JSON은 DTO 검증에 실패할 요청이지만,
         * 유효한 CSRF 토큰을 포함해 인증 실패가 본문 검증보다
         * 먼저 처리되는지 확인
         */
        mockMvc.perform(
                post(path)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/users/11111111-1111-1111-1111-111111111111"
            + "/local-credentials/email-verifications",
        "/api/users/11111111-1111-1111-1111-111111111111"
            + "/local-credentials"
    })
    @DisplayName("로컬 로그인 등록 API는 CSRF 토큰이 없으면 403을 반환한다")
    void localCredentialApi_withoutCsrf_returnsForbidden(
        String path
    ) throws Exception {

        mockMvc.perform(
                post(path)
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer valid-token"
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isForbidden())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_403_1")
            );

        /*
         * CSRF 필터가 JWT 필터보다 먼저 요청을 차단
         */
        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/users/11111111-1111-1111-1111-111111111111"
            + "/local-credentials/email-verifications",
        "/api/users/11111111-1111-1111-1111-111111111111"
            + "/local-credentials"
    })
    @DisplayName("로컬 로그인 등록 API는 유효한 JWT와 CSRF 이후 본문을 검증한다")
    void localCredentialApi_withJwtAndCsrf_validatesBody(
        String path
    ) throws Exception {

        var authentication =
            UsernamePasswordAuthenticationToken
                .authenticated(
                    UUID.fromString(USER_ID),
                    null,
                    List.of(
                        new SimpleGrantedAuthority(
                            "ROLE_USER"
                        )
                    )
                );

        when(jwtProvider.validate("valid-token"))
            .thenReturn(true);

        when(
            jwtProvider.getAuthentication(
                "valid-token"
            )
        ).thenReturn(authentication);

        /*
         * 보안 필터는 통과하지만 빈 JSON은 DTO의 @NotBlank 등에
         * 위반되므로 Controller 진입 과정에서 400을 반환해야 한다.
         */
        mockMvc.perform(
                post(path)
                    .with(csrf())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer valid-token"
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isBadRequest());

        verify(jwtProvider)
            .validate("valid-token");

        verify(jwtProvider)
            .getAuthentication("valid-token");
    }

    @Test
    @DisplayName("로그아웃은 유효한 JWT가 있어도 CSRF 토큰이 없으면 403을 반환한다")
    void signOut_withoutCsrf_returnsForbidden()
        throws Exception {

        /*
         * 유효한 형태의 Bearer Token을 전달하더라도
         * CSRF 토큰이 없으면 상태 변경 요청을 허용하면 안된다.
         *
         * CsrfFilter는 JwtAuthenticationFilter보다 먼저 실행되므로
         * 요청은 JWT 검증 단계에 도달하기 전에 차단
         */
        mockMvc.perform(
                post("/api/auth/sign-out")
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer valid-token"
                    )
            )
            .andExpect(status().isForbidden())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_403_1")
            );

        /*
         * CSRF 검증에서 요청이 차단됐으므로
         * 불필요한 JWT 검증도 실행되지 않아야 합니다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("일반 사용자가 계정 잠금 API에 잘못된 본문을 보내도 403을 반환한다")
    void updateLocked_userWithInvalidBody_returnsForbidden() throws Exception {
        // given
        /*
         * JWT 인증은 성공했지만 ROLE_USER 권한만 가진 인증 객체를 생성
         *
         * 이 요청은 인증에는 성공하지만 관리자 권한은 없으므로
         * Controller와 DTO 검증에 도달하기 전에 SecurityFilterChain에서
         * 403 Forbidden으로 차단되어야 한다.
         */
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.fromString(USER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

        when(jwtProvider.validate("user-token"))
            .thenReturn(true);
        when(jwtProvider.getAuthentication("user-token"))
            .thenReturn(authentication);

        // when & then
        /*
         * 빈 JSON 객체에는 필수 locked 값이 없으므로
         * Controller까지 도달하면 @Valid 검증에 의해 400이 발생
         *
         * 하지만 일반 사용자는 관리자 권한이 없으므로 요청 본문 검증보다
         * 먼저 SecurityFilterChain에서 차단되어 반드시 403이 반환되어야 한다.
         *
         * with(csrf())를 넣지 않으면 CSRF 필터가 요청을 차단하여 403이 발생할수 있으므로
         * 관리자 권한 검사로 인한 403인지 구분할 수 없다.
         * 따라서 유효한 CSRF 토큰을 포함해 권한 검사 결과만 검증
         */
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/locked",
                    USER_ID
                )
                    .with(csrf())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer user-token"
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isForbidden())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_403_1")
            );

        /*
         * JWT가 정상적으로 검증되고 인증 객체로 변환되었는지도 확인
         * 즉, 이번 403이 인증 실패가 아니라 권한 부족으로 발생했음을 보장
         */
        verify(jwtProvider).validate("user-token");
        verify(jwtProvider).getAuthentication("user-token");
    }

    @Test
    @DisplayName("관리자가 계정 잠금 API에 잘못된 본문을 보내면 400을 반환한다")
    void updateLocked_adminWithInvalidBody_returnsBadRequest() throws Exception {
        // given
        /*
         * ROLE_ADMIN 권한을 가진 인증 객체를 생성
         * 관리자는 SecurityFilterChain의 관리자 권한 검사를 통과해야 한다.
         */
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.fromString(USER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );

        when(jwtProvider.validate("admin-token"))
            .thenReturn(true);
        when(jwtProvider.getAuthentication("admin-token"))
            .thenReturn(authentication);

        // when & then
        /*
         * 관리자는 권한 검사를 통과하므로 요청이 Controller까지 전달
         * 이후 locked 값이 없는 빈 JSON 본문이 DTO 검증에 실패하여
         * 400 Bad Request가 반환되어야 한다.
         *
         * 이 테스트는 일반 사용자의 403이 단순히 잘못된 본문 때문에
         * 발생한 것이 아니라는 점을 함께 증명
         */
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/locked",
                    USER_ID
                )
                    .with(csrf())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer admin-token"
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isBadRequest());

        verify(jwtProvider).validate("admin-token");
        verify(jwtProvider).getAuthentication("admin-token");
    }

    @Test
    @DisplayName("인증되지 않은 사용자가 계정 잠금 API에 잘못된 본문을 보내도 401을 반환한다")
    void updateLocked_unauthenticatedWithInvalidBody_returnsUnauthorized()
        throws Exception {

        // given
        /*
         * Authorization 헤더를 전달하지 않아 인증 정보가 없는 요청을 구성한다.
         *
         * 빈 JSON 본문은 locked 값이 없어 DTO 검증에 실패할 요청이지만,
         * 인증되지 않은 요청은 본문 검증보다 먼저 SecurityFilterChain에서
         * 401 Unauthorized로 차단되어야 한다.
         */

        // when & then
        /*
         * 유효한 CSRF 토큰을 포함해야 CSRF 실패로 인한 403과
         * 인증 실패로 인한 401을 정확하게 구분할 수 있다.
         */
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/locked",
                    USER_ID
                )
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        /*
         * Bearer 토큰이 전달되지 않았으므로 JwtProvider를 통한
         * 토큰 검증도 실행되지 않아야 한다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("일반 사용자가 권한 변경 API에 잘못된 본문을 보내도 403을 반환한다")
    void updateRole_userWithInvalidBody_returnsForbidden()
        throws Exception {

        // given
        /*
         * JWT 인증은 성공했지만 ROLE_USER 권한만 가진 인증 객체를 생성한다.
         *
         * 요청자는 인증된 사용자이지만 관리자가 아니므로
         * Controller와 DTO 검증에 도달하기 전에 403으로 차단되어야 한다.
         */
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.fromString(USER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );

        when(jwtProvider.validate("user-token"))
            .thenReturn(true);

        when(jwtProvider.getAuthentication("user-token"))
            .thenReturn(authentication);

        // when & then
        /*
         * 빈 JSON에는 필수 role 값이 없으므로 Controller까지 도달하면
         * @Valid 검증에 의해 400 Bad Request가 발생한다.
         *
         * 하지만 일반 사용자는 관리자 권한이 없으므로
         * 본문 검증보다 먼저 SecurityFilterChain에서 차단되어
         * 반드시 403 Forbidden이 반환되어야 한다.
         *
         * CSRF 토큰을 포함하지 않으면 CSRF 필터가 먼저 403을 반환할 수 있다.
         * 관리자 권한 부족으로 발생한 403임을 확인하기 위해
         * 유효한 CSRF 토큰을 함께 전달한다.
         */
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/role",
                    USER_ID
                )
                    .with(csrf())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer user-token"
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isForbidden())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_403_1")
            );

        /*
         * JWT 검증과 Authentication 생성까지 성공했는지 확인한다.
         * 이를 통해 인증 실패가 아니라 권한 부족으로 발생한 403임을 보장한다.
         */
        verify(jwtProvider).validate("user-token");
        verify(jwtProvider).getAuthentication("user-token");
    }

    @Test
    @DisplayName("관리자가 권한 변경 API에 잘못된 본문을 보내면 400을 반환한다")
    void updateRole_adminWithInvalidBody_returnsBadRequest()
        throws Exception {

        // given
        /*
         * ROLE_ADMIN 권한을 가진 인증 객체를 생성한다.
         *
         * 관리자는 SecurityFilterChain의 관리자 권한 검사를 통과하고
         * 요청이 Controller까지 전달되어야 한다.
         */
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                UUID.fromString(USER_ID),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            );

        when(jwtProvider.validate("admin-token"))
            .thenReturn(true);

        when(jwtProvider.getAuthentication("admin-token"))
            .thenReturn(authentication);

        // when & then
        /*
         * 관리자는 보안 필터의 권한 검사를 통과한다.
         *
         * 이후 role 값이 없는 빈 JSON이
         * UserRoleUpdateRequest의 @NotNull 검증에 실패하여
         * 400 Bad Request가 반환되어야 한다.
         */
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/role",
                    USER_ID
                )
                    .with(csrf())
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer admin-token"
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isBadRequest());

        verify(jwtProvider).validate("admin-token");
        verify(jwtProvider).getAuthentication("admin-token");
    }

    @Test
    @DisplayName("인증되지 않은 사용자가 권한 변경 API에 잘못된 본문을 보내도 401을 반환한다")
    void updateRole_unauthenticatedWithInvalidBody_returnsUnauthorized() throws Exception {

        // given
        /*
         * Authorization 헤더를 전달하지 않아
         * 인증 정보가 없는 요청을 구성한다.
         *
         * 빈 JSON은 role 값이 없어 DTO 검증에 실패할 요청이지만,
         * 인증되지 않은 요청은 본문 검증보다 먼저 차단되어야 한다.
         */

        // when & then
        /*
         * CSRF 토큰을 포함해야 CSRF 실패 403과
         * 인증 실패 401을 정확하게 구분할 수 있다.
         */
        mockMvc.perform(
                patch(
                    "/api/users/{userId}/role",
                    USER_ID
                )
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        /*
         * Bearer 토큰 자체가 전달되지 않았으므로
         * JwtProvider의 토큰 검증도 실행되지 않아야 한다.
         */
        verify(
            jwtProvider,
            never()
        ).validate(org.mockito.ArgumentMatchers.anyString());
    }

}
