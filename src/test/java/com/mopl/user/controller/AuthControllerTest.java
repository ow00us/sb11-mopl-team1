package com.mopl.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.user.dto.JwtDto;
import com.mopl.user.dto.SignInRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.entity.UserRole;
import com.mopl.user.service.AuthService;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 이메일 로그인 HTTP API의 요청·응답 형식과 입력값 검증을 확인
 *
 * AuthService는 Mock으로 대체
 * 따라서 이 테스트는 HTTP 상태 코드, JSON 변환, Bean Validation에 집중
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    AuthService authService;

    @Test
    @DisplayName("로그인 성공 시 200과 액세스 토큰을 반환한다")
    void signIn_success() throws Exception {
        // given
        UUID userId =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

        Instant createdAt =
            Instant.parse("2026-07-31T03:00:00Z");

        Map<String, String> request = Map.of(
            "email", "user@example.com",
            "password", "passwordTest1!"
        );

        UserDto userDto = new UserDto(
            userId,
            createdAt,
            "user@example.com",
            "테스트 사용자",
            "https://example.com/profile.png",
            UserRole.USER,
            false
        );

        when(authService.signIn(any(SignInRequest.class)))
            .thenReturn(new JwtDto(userDto, "access-token"));

        // when & then
        mockMvc.perform(post("/api/auth/sign-in")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.accessToken").value("access-token"))
            .andExpect(jsonPath("$.userDto.id").value(userId.toString()))
            .andExpect(jsonPath("$.userDto.email").value("user@example.com"))
            .andExpect(jsonPath("$.userDto.name").value("테스트 사용자"))
            .andExpect(jsonPath("$.userDto.profileImageUrl")
                .value("https://example.com/profile.png"))
            .andExpect(jsonPath("$.userDto.role").value("USER"))
            .andExpect(jsonPath("$.userDto.locked").value(false));

        /*
         * JSON 요청 본문이 SignInRequest로 올바르게 변환되어
         * 서비스에 전달됐는지까지 검증
         */
        verify(authService).signIn(
            new SignInRequest(
                "user@example.com",
                "passwordTest1!"
            )
        );
    }

    @Test
    @DisplayName("이메일이 비어 있으면 400을 반환하고 로그인 처리를 수행하지 않는다")
    void signIn_fail_whenEmailBlank() throws Exception {
        // given
        Map<String, String> request = Map.of(
            "email", "",
            "password", "passwordTest1!"
        );

        // when & then
        mockMvc.perform(post("/api/auth/sign-in")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"))
            .andExpect(jsonPath("$.details.email").exists());

        /*
         * @Valid 검증에서 요청이 차단됐으므로,
         * 서비스의 인증·JWT 발급 로직은 호출되면 안 됨
         */
        verifyNoInteractions(authService);
    }
}
