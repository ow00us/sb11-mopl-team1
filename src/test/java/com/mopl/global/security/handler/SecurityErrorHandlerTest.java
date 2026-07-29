package com.mopl.global.security.handler;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.config.SecurityConfig;
import com.mopl.global.security.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(SecurityErrorHandlerTest.ProtectedController.class)
@Import({
    SecurityConfig.class,
    SecurityErrorHandlerTest.ProtectedEndpointSecurityConfig.class
})
class SecurityErrorHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtProvider jwtProvider;

    @Test
    @DisplayName("인증 정보가 없는 보호 요청은 공통 ErrorResponse와 401을 반환한다")
    void unauthenticatedRequest_returnsCommonUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/test/security/authenticated"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.exceptionName").isNotEmpty())
            .andExpect(jsonPath("$.errorCode").value("COMMON_401_1"))
            .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
            .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    @DisplayName("권한이 부족한 요청은 공통 ErrorResponse와 403을 반환한다")
    void insufficientAuthority_returnsCommonForbiddenResponse() throws Exception {
        mockMvc.perform(get("/test/security/admin")
                .with(user("user").roles("USER")))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.exceptionName").isNotEmpty())
            .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"))
            .andExpect(jsonPath("$.message").value("권한이 없습니다."))
            .andExpect(jsonPath("$.details").isEmpty());
    }

    @RestController
    static class ProtectedController {

        @GetMapping("/test/security/authenticated")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void authenticated() {
        }

        @GetMapping("/test/security/admin")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void admin() {
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtectedEndpointSecurityConfig {

        @Bean
        @Order(0)
        SecurityFilterChain protectedEndpointFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler
        ) throws Exception {
            http
                .securityMatcher("/test/security/**")
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(exception -> exception
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/test/security/admin").hasRole("ADMIN")
                    .anyRequest().authenticated());

            return http.build();
        }
    }
}
