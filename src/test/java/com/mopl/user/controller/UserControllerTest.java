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
import com.mopl.user.dto.UserCreateRequest;
import com.mopl.user.dto.UserDto;
import com.mopl.user.entity.UserRole;
import com.mopl.user.service.UserService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 회원가입 HTTP API를 검증하는 Controller 테스트
 *
 * UserService는 Mock으로 대체
 * 이 테스트는 HTTP 요청, JSON 변환, Bean Validation, HTTP 상태 코드와 응답 형식만 검증
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    UserService userService;

    @Test
    @DisplayName("회원가입 성공 시 201과 생성된 사용자 정보를 반환한다")
    void signUp_success() throws Exception {
        // given
        UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Instant createdAt = Instant.parse("2026-07-28T03:00:00Z");

        Map<String, String> request = Map.of(
            "name", "테스트 사용자",
            "email", "user@example.com",
            "password", "passwordTest1!"
        );

        UserDto response = new UserDto(
            userId,
            createdAt,
            "user@example.com",
            "테스트 사용자",
            null,
            UserRole.USER,
            false
        );

        when(userService.signUp(any(UserCreateRequest.class))).thenReturn(response);

        // when & then
        mockMvc.perform(post("/api/users")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.id").value(userId.toString()))
            .andExpect(jsonPath("$.email").value("user@example.com"))
            .andExpect(jsonPath("$.name").value("테스트 사용자"))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.locked").value(false));

        verify(userService).signUp(
            new UserCreateRequest(
                "테스트 사용자",
                "user@example.com",
                "passwordTest1!"
            )
        );
    }

    @Test
    @DisplayName("이메일이 비어 있으면 400을 반환하고 회원가입을 수행하지 않는다")
    void signUp_fail_whenEmailBlank() throws Exception {
        // given
        Map<String, String> request = Map.of(
            "name", "테스트 사용자",
            "email", "",
            "password", "passwordTest1!"
        );

        // when & then
        mockMvc.perform(post("/api/users")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"))
            .andExpect(jsonPath("$.details.email").exists());

        // Controller의 입력 검증에서 막혀야 하므로 Service는 호출되면 안 됩니다.
        verifyNoInteractions(userService);
    }
}
