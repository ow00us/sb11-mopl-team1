package com.mopl.global.security.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.config.SecurityConfig;
import com.mopl.global.security.JwtProvider;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(CsrfTokenController.class)
@Import({
    SecurityConfig.class,
    CsrfTokenControllerTest.StateChangingController.class
})
class CsrfTokenControllerTest {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtProvider jwtProvider;

    @Test
    @DisplayName("인증 없이 CSRF 토큰을 쿠키로 발급하고 204를 반환한다")
    void getCsrfToken_issuesReadableCookie() throws Exception {
        MvcResult result = requestCsrfToken();

        Cookie csrfCookie = result.getResponse().getCookie(CSRF_COOKIE_NAME);

        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.getValue()).isNotBlank();
        assertThat(csrfCookie.isHttpOnly()).isFalse();
        assertThat(csrfCookie.getPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("쿠키와 헤더에 같은 CSRF 토큰을 보내면 상태 변경 요청을 허용한다")
    void stateChangingRequest_withMatchingToken_isAllowed() throws Exception {
        Cookie csrfCookie = getCsrfCookie();

        mockMvc.perform(post("/test/csrf-protected")
                .cookie(csrfCookie)
                .header(CSRF_HEADER_NAME, csrfCookie.getValue()))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("CSRF 토큰이 없으면 상태 변경 요청에 403을 반환한다")
    void stateChangingRequest_withoutToken_isForbidden() throws Exception {
        mockMvc.perform(post("/test/csrf-protected"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.exceptionName").isNotEmpty())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"))
            .andExpect(jsonPath("$.message").value("권한이 없습니다."))
            .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    @DisplayName("쿠키와 헤더의 CSRF 토큰이 다르면 상태 변경 요청에 403을 반환한다")
    void stateChangingRequest_withMismatchedToken_isForbidden() throws Exception {
        Cookie csrfCookie = getCsrfCookie();

        mockMvc.perform(post("/test/csrf-protected")
                .cookie(csrfCookie)
                .header(CSRF_HEADER_NAME, "mismatched-token"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.exceptionName").isNotEmpty())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"))
            .andExpect(jsonPath("$.message").value("권한이 없습니다."))
            .andExpect(jsonPath("$.details").isEmpty());
    }

    private MvcResult requestCsrfToken() throws Exception {
        return mockMvc.perform(get("/api/auth/csrf-token"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""))
            .andExpect(cookie().exists(CSRF_COOKIE_NAME))
            .andReturn();
    }

    private Cookie getCsrfCookie() throws Exception {
        Cookie csrfCookie = requestCsrfToken()
            .getResponse()
            .getCookie(CSRF_COOKIE_NAME);

        assertThat(csrfCookie).isNotNull();
        return csrfCookie;
    }

    @RestController
    static class StateChangingController {

        @PostMapping("/test/csrf-protected")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void update() {
        }
    }
}
