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

@WebMvcTest(SecurityPolicyProbeController.class)
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
        "/api/auth/sign-in"
    })
    @DisplayName("공개 POST API는 CSRF 토큰이 있으면 JWT 없이 접근할 수 있다")
    void publicPost_withCsrf_doesNotRequireJwt(String path) throws Exception {
        mockMvc.perform(post(path).with(csrf()))
            .andExpect(status().isNoContent());

        verify(jwtProvider, never()).validate(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("공개 POST API도 CSRF 토큰이 없으면 403을 반환한다")
    void publicPost_withoutCsrf_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/users"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    @Test
    @DisplayName("CSRF 토큰 발급 API는 JWT 없이 접근할 수 있다")
    void csrfTokenEndpoint_doesNotRequireJwt() throws Exception {
        mockMvc.perform(get("/api/auth/csrf-token"))
            .andExpect(status().isNoContent());
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

}
