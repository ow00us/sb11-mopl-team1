package com.mopl.global.security;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

}
