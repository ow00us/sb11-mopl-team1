package com.mopl.follow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowRequest;
import com.mopl.follow.service.FollowService;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FollowController.class)
@AutoConfigureMockMvc(addFilters = false)
class FollowControllerTest {

    private static final UUID FOLLOWER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FOLLOWEE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID FOLLOW_ID   = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean FollowService followService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── POST /api/follows ─────────────────────────────────────────────────────

    @Test
    @DisplayName("팔로우 성공 시 201과 FollowDto 를 반환한다")
    void follow_success() throws Exception {
        setAuth(FOLLOWER_ID);
        FollowDto response = new FollowDto(FOLLOW_ID, FOLLOWEE_ID, FOLLOWER_ID);
        when(followService.follow(FOLLOWER_ID, FOLLOWEE_ID)).thenReturn(response);

        mockMvc.perform(post("/api/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FollowRequest(FOLLOWEE_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(FOLLOW_ID.toString()))
                .andExpect(jsonPath("$.followeeId").value(FOLLOWEE_ID.toString()))
                .andExpect(jsonPath("$.followerId").value(FOLLOWER_ID.toString()));
    }

    @Test
    @DisplayName("자기 자신 팔로우 시도 시 400 을 반환한다")
    void follow_fail_self() throws Exception {
        setAuth(FOLLOWER_ID);
        when(followService.follow(FOLLOWER_ID, FOLLOWER_ID))
                .thenThrow(new BusinessException(ErrorCode.FOLLOW_SELF));

        mockMvc.perform(post("/api/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FollowRequest(FOLLOWER_ID))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("FOLLOW_400_1"));
    }

    @Test
    @DisplayName("중복 팔로우 시도 시 409 를 반환한다")
    void follow_fail_duplicate() throws Exception {
        setAuth(FOLLOWER_ID);
        when(followService.follow(FOLLOWER_ID, FOLLOWEE_ID))
                .thenThrow(new BusinessException(ErrorCode.FOLLOW_DUPLICATE));

        mockMvc.perform(post("/api/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FollowRequest(FOLLOWEE_ID))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("FOLLOW_409_1"));
    }

    @Test
    @DisplayName("미인증 상태에서 팔로우 시도 시 401 을 반환한다")
    void follow_fail_unauthorized() throws Exception {
        mockMvc.perform(post("/api/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FollowRequest(FOLLOWEE_ID))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(followService);
    }

    // ── DELETE /api/follows/{followId} ────────────────────────────────────────

    @Test
    @DisplayName("팔로우 취소 성공 시 204 를 반환한다")
    void unfollow_success() throws Exception {
        setAuth(FOLLOWER_ID);
        doNothing().when(followService).unfollow(FOLLOW_ID, FOLLOWER_ID);

        mockMvc.perform(delete("/api/follows/{followId}", FOLLOW_ID))
                .andExpect(status().isNoContent());

        verify(followService).unfollow(FOLLOW_ID, FOLLOWER_ID);
    }

    @Test
    @DisplayName("본인 팔로우가 아닌 취소 시도 시 403 을 반환한다")
    void unfollow_fail_forbidden() throws Exception {
        setAuth(FOLLOWER_ID);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(followService).unfollow(FOLLOW_ID, FOLLOWER_ID);

        mockMvc.perform(delete("/api/follows/{followId}", FOLLOW_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    @Test
    @DisplayName("존재하지 않는 팔로우 취소 시 404 를 반환한다")
    void unfollow_fail_notFound() throws Exception {
        setAuth(FOLLOWER_ID);
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(followService).unfollow(FOLLOW_ID, FOLLOWER_ID);

        mockMvc.perform(delete("/api/follows/{followId}", FOLLOW_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    @Test
    @DisplayName("미인증 상태에서 팔로우 취소 시도 시 401 을 반환한다")
    void unfollow_fail_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/follows/{followId}", FOLLOW_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(followService);
    }

    // ── GET /api/follows/count ────────────────────────────────────────────────

    @Test
    @DisplayName("팔로워 수 조회 성공 시 200과 count 를 반환한다")
    void countFollowers_success() throws Exception {
        when(followService.countFollowers(FOLLOWEE_ID)).thenReturn(5L);

        mockMvc.perform(get("/api/follows/count")
                        .param("followeeId", FOLLOWEE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string("5"));
    }

    // ── GET /api/follows/followed-by-me ──────────────────────────────────────

    @Test
    @DisplayName("팔로우 중이면 200과 FollowDto 를 반환한다")
    void getFollowedByMe_success() throws Exception {
        setAuth(FOLLOWER_ID);
        FollowDto response = new FollowDto(FOLLOW_ID, FOLLOWEE_ID, FOLLOWER_ID);
        when(followService.getFollowedByMe(FOLLOWER_ID, FOLLOWEE_ID)).thenReturn(response);

        mockMvc.perform(get("/api/follows/followed-by-me")
                        .param("followeeId", FOLLOWEE_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FOLLOW_ID.toString()));
    }

    @Test
    @DisplayName("팔로우 중이 아니면 404 를 반환한다")
    void getFollowedByMe_fail_notFollowing() throws Exception {
        setAuth(FOLLOWER_ID);
        when(followService.getFollowedByMe(FOLLOWER_ID, FOLLOWEE_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/follows/followed-by-me")
                        .param("followeeId", FOLLOWEE_ID.toString()))
                .andExpect(status().isNotFound());
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void setAuth(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}