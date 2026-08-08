package com.mopl.global.exception;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.config.SecurityConfig;
import com.mopl.global.security.JwtProvider;
import java.util.List;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 클라이언트 요청 오류가 4xx 로 반환되는지 검증합니다.
 *
 * GlobalExceptionHandler 에 핸들러가 없는 스프링 표준 예외는
 * {@code @ExceptionHandler(Exception.class)} 폴백으로 떨어져 500 과 COMMON_500_1 이
 * 됩니다. 잘못된 경로나 형식으로 보낸 요청이 서버 오류로 보고되면 클라이언트는
 * 재시도 대상으로 오인하고, 서버 오류 지표도 오염됩니다.
 */
@WebMvcTest(ExceptionMappingProbeController.class)
@ActiveProfiles({"test", "exception-mapping-test"})
@Import({
    SecurityConfig.class,
    GlobalExceptionHandler.class
})
class ClientErrorStatusMappingTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtProvider jwtProvider;

    @Test
    @DisplayName("매핑되지 않은 경로는 404를 반환한다")
    void unmatchedPath_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/definitely-not-a-route").with(user()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    @Test
    @DisplayName("매핑되지 않은 하위 경로는 404를 반환한다")
    void unmatchedNestedPath_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/exception-probe/resource/nope").with(user()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    @Test
    @DisplayName("허용하지 않는 HTTP 메서드는 405를 반환한다")
    void unsupportedMethod_returnsMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/api/exception-probe/resource").with(user()).with(csrf()))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.errorCode").value("COMMON_405_1"));
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type은 415를 반환한다")
    void unsupportedMediaType_returnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(patch("/api/exception-probe/json")
                .with(user())
                .with(csrf())
                .contentType(MediaType.TEXT_PLAIN)
                .content("locked=true"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.errorCode").value("COMMON_415_1"));
    }

    private RequestPostProcessor user() {
        return authentication(new UsernamePasswordAuthenticationToken(
            USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
