package com.mopl.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.mopl.global.config.SecurityConfig;
import com.mopl.global.security.controller.CsrfTokenController;
import com.mopl.user.service.AccessTokenUserStatusService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CSRF 토큰 쿠키가 요청 사이에 유지되는지 검증합니다.
 *
 * 브라우저는 발급받은 쿠키를 이어서 보내므로, 한 번 발급된 토큰은 이후 상태 변경
 * 요청에서도 계속 쓸 수 있어야 합니다. 성공 응답이 쿠키를 지우면 다음 요청이 토큰
 * 없이 전송되어 403 으로 실패하고, 그 403 이 새 토큰을 발급해 그 다음 요청은 다시
 * 성공하는 교대 현상이 생깁니다.
 */
@WebMvcTest(SecurityPolicyProbeController.class)
@ActiveProfiles({"test", "security-policy-test"})
@Import({
    SecurityConfig.class,
    CsrfTokenController.class
})
class CsrfTokenCookieLifecycleTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final String ADMIN_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtProvider jwtProvider;

    @MockitoBean
    AccessTokenUserStatusService accessTokenUserStatusService;

    @Test
    @DisplayName("발급받은 CSRF 토큰으로 상태 변경 요청을 연속으로 보낼 수 있다")
    void csrfToken_survivesConsecutiveStateChangingRequests() throws Exception {
        Cookie token = issueCsrfCookie();

        for (int attempt = 1; attempt <= 3; attempt++) {
            MvcResult result = mockMvc.perform(stateChangingRequest(token)).andReturn();

            assertThat(result.getResponse().getStatus())
                .as("%d번째 상태 변경 요청", attempt)
                .isEqualTo(204);

            token = carryOver(token, result);
            assertThat(token)
                .as("%d번째 요청 후 다음 요청에 쓸 CSRF 토큰", attempt)
                .isNotNull();
        }
    }

    @Test
    @DisplayName("상태 변경 요청의 성공 응답은 CSRF 토큰 쿠키를 지우지 않는다")
    void successfulRequest_doesNotClearCsrfCookie() throws Exception {
        Cookie token = issueCsrfCookie();

        MvcResult result = mockMvc.perform(stateChangingRequest(token)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);

        Cookie responseCookie = result.getResponse().getCookie(CSRF_COOKIE);
        if (responseCookie != null) {
            // 값을 교체하는 것은 허용합니다. 빈 값이나 즉시 만료로 지우는 것만 막습니다.
            assertThat(responseCookie.getValue()).isNotEmpty();
            assertThat(responseCookie.getMaxAge()).isNotZero();
        }
    }

    private Cookie issueCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf-token")).andReturn();

        Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
        assertThat(cookie).as("csrf-token 발급 응답의 쿠키").isNotNull();
        assertThat(cookie.getValue()).isNotEmpty();
        return cookie;
    }

    private MockHttpServletRequestBuilder stateChangingRequest(Cookie token) {
        return patch("/api/users/{userId}/locked", UUID.randomUUID())
            .cookie(token)
            .header(CSRF_HEADER, token.getValue())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"locked\":true}")
            .with(authentication(new UsernamePasswordAuthenticationToken(
                ADMIN_ID, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))));
    }

    /**
     * 브라우저의 쿠키 처리를 흉내 냅니다. 응답이 새 쿠키를 내려주면 그것을, 아무 것도
     * 내려주지 않으면 기존 쿠키를 그대로 씁니다. 값이 비었거나 즉시 만료면 브라우저에서
     * 삭제되므로 다음 요청에 쓸 토큰이 없다는 뜻으로 null 을 돌려줍니다.
     */
    private Cookie carryOver(Cookie current, MvcResult result) {
        Cookie next = result.getResponse().getCookie(CSRF_COOKIE);
        if (next == null) {
            return current;
        }
        if (next.getValue() == null || next.getValue().isEmpty() || next.getMaxAge() == 0) {
            return null;
        }
        return next;
    }
}
