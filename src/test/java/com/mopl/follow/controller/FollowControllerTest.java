package com.mopl.follow.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowRequest;
import com.mopl.follow.dto.FollowUserItemDto;
import com.mopl.follow.service.FollowResult;
import com.mopl.follow.service.FollowService;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
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

import java.time.Instant;
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
    @DisplayName("신규 팔로우 성공 시 201과 FollowDto 를 반환한다")
    void follow_success_new_returns201() throws Exception {
        setAuth(FOLLOWER_ID);
        FollowDto dto = new FollowDto(FOLLOW_ID, FOLLOWEE_ID, FOLLOWER_ID);
        when(followService.follow(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(new FollowResult(dto, true));

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
    @DisplayName("중복 팔로우 시도 시 200과 기존 FollowDto 를 반환한다 (ADR 2 계약)")
    void follow_duplicate_returns200WithExistingDto() throws Exception {
        setAuth(FOLLOWER_ID);
        FollowDto existing = new FollowDto(FOLLOW_ID, FOLLOWEE_ID, FOLLOWER_ID);
        when(followService.follow(FOLLOWER_ID, FOLLOWEE_ID))
                .thenReturn(new FollowResult(existing, false));

        mockMvc.perform(post("/api/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FollowRequest(FOLLOWEE_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FOLLOW_ID.toString()))
                .andExpect(jsonPath("$.followeeId").value(FOLLOWEE_ID.toString()))
                .andExpect(jsonPath("$.followerId").value(FOLLOWER_ID.toString()));
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
                .andExpect(jsonPath("$.count").value(5));
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

    // ── GET /api/follows/followers ────────────────────────────────────────────

    @Test
    @DisplayName("팔로워 목록 조회 성공 시 200과 hasNext/nextCursor/nextIdAfter 포함한 CursorResponse 를 반환한다")
    void getFollowers_success() throws Exception {
        Instant now  = Instant.parse("2026-08-01T10:00:00Z");
        Instant next = Instant.parse("2026-08-01T09:00:00Z");
        UUID otherFollowerId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        UUID nextIdAfter = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String nextCursor = "encoded-cursor-token";

        CursorResponse<FollowUserItemDto> response = CursorResponse.of(
                List.of(
                        new FollowUserItemDto(FOLLOW_ID, new UserSummary(FOLLOWER_ID, null, null), now),
                        new FollowUserItemDto(nextIdAfter, new UserSummary(otherFollowerId, null, null), next)
                ),
                nextCursor, nextIdAfter, true, 5L, "followedAt", "DESCENDING");
        when(followService.getFollowers(eq(FOLLOWEE_ID), any(), any(), eq(2),
                eq("followedAt"), eq("DESCENDING"))).thenReturn(response);

        mockMvc.perform(get("/api/follows/followers")
                        .param("followeeId", FOLLOWEE_ID.toString())
                        .param("limit", "2")
                        .param("sortBy", "followedAt")
                        .param("sortDirection", "DESCENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].followId").value(FOLLOW_ID.toString()))
                .andExpect(jsonPath("$.data[0].user.userId").value(FOLLOWER_ID.toString()))
                .andExpect(jsonPath("$.data[0].followedAt").value(now.toString()))
                .andExpect(jsonPath("$.data[1].followId").value(nextIdAfter.toString()))
                .andExpect(jsonPath("$.data[1].followedAt").value(next.toString()))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").value(nextCursor))
                .andExpect(jsonPath("$.nextIdAfter").value(nextIdAfter.toString()))
                .andExpect(jsonPath("$.totalCount").value(5));
    }

    @Test
    @DisplayName("팔로워 목록 조회 시 limit 이 0 이하면 400 을 반환한다")
    void getFollowers_fail_invalidLimit() throws Exception {
        mockMvc.perform(get("/api/follows/followers")
                        .param("followeeId", FOLLOWEE_ID.toString())
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("팔로워 목록 조회 시 limit 이 100 초과면 400 을 반환한다")
    void getFollowers_fail_limitExceedsMax() throws Exception {
        mockMvc.perform(get("/api/follows/followers")
                        .param("followeeId", FOLLOWEE_ID.toString())
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("팔로워 목록 조회 시 sortDirection 이 허용값이 아니면 400 을 반환한다")
    void getFollowers_fail_invalidSortDirection() throws Exception {
        mockMvc.perform(get("/api/follows/followers")
                        .param("followeeId", FOLLOWEE_ID.toString())
                        .param("limit", "10")
                        .param("sortDirection", "WRONG"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("팔로워 목록 조회 시 sortDirection=ASCENDING 은 현재 미지원이므로 400 과 INVALID_INPUT 을 반환한다")
    void getFollowers_fail_ascendingNotSupported() throws Exception {
        mockMvc.perform(get("/api/follows/followers")
                        .param("followeeId", FOLLOWEE_ID.toString())
                        .param("limit", "10")
                        .param("sortDirection", "ASCENDING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));
    }

    @Test
    @DisplayName("팔로워 목록 조회 시 followeeId 파라미터가 없으면 400 을 반환한다")
    void getFollowers_fail_missingFolloweeId() throws Exception {
        mockMvc.perform(get("/api/follows/followers")
                        .param("limit", "10"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/follows/followings ───────────────────────────────────────────

    @Test
    @DisplayName("팔로잉 목록 조회 성공 시 200과 hasNext/nextCursor/nextIdAfter/totalCount 포함한 CursorResponse 를 반환한다")
    void getFollowings_success() throws Exception {
        Instant now  = Instant.parse("2026-08-01T10:00:00Z");
        Instant next = Instant.parse("2026-08-01T09:00:00Z");
        UUID otherFolloweeId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        UUID nextIdAfter = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String nextCursor = "encoded-cursor-token";

        CursorResponse<FollowUserItemDto> response = CursorResponse.of(
                List.of(
                        new FollowUserItemDto(FOLLOW_ID, new UserSummary(FOLLOWEE_ID, null, null), now),
                        new FollowUserItemDto(nextIdAfter, new UserSummary(otherFolloweeId, null, null), next)
                ),
                nextCursor, nextIdAfter, true, 3L, "followedAt", "DESCENDING");
        when(followService.getFollowings(eq(FOLLOWER_ID), any(), any(), eq(2),
                eq("followedAt"), eq("DESCENDING"))).thenReturn(response);

        mockMvc.perform(get("/api/follows/followings")
                        .param("followerId", FOLLOWER_ID.toString())
                        .param("limit", "2")
                        .param("sortBy", "followedAt")
                        .param("sortDirection", "DESCENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].followId").value(FOLLOW_ID.toString()))
                .andExpect(jsonPath("$.data[0].user.userId").value(FOLLOWEE_ID.toString()))
                .andExpect(jsonPath("$.data[0].followedAt").value(now.toString()))
                .andExpect(jsonPath("$.data[1].followId").value(nextIdAfter.toString()))
                .andExpect(jsonPath("$.data[1].followedAt").value(next.toString()))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").value(nextCursor))
                .andExpect(jsonPath("$.nextIdAfter").value(nextIdAfter.toString()))
                .andExpect(jsonPath("$.totalCount").value(3));
    }

    @Test
    @DisplayName("팔로잉 목록 조회 시 followerId 가 없으면 400 을 반환한다")
    void getFollowings_fail_missingFollowerId() throws Exception {
        mockMvc.perform(get("/api/follows/followings")
                        .param("limit", "10"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("팔로잉 목록 조회 시 sortDirection=ASCENDING 은 현재 미지원이므로 400 과 INVALID_INPUT 을 반환한다")
    void getFollowings_fail_ascendingNotSupported() throws Exception {
        mockMvc.perform(get("/api/follows/followings")
                        .param("followerId", FOLLOWER_ID.toString())
                        .param("limit", "10")
                        .param("sortDirection", "ASCENDING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));
    }

    // ── 인증 예외 경로 (resolveUserId) ─────────────────────────────────────

    @Test
    @DisplayName("인증 사용자 이름이 UUID 형식이 아니면 401 을 반환한다")
    void follow_fail_whenPrincipalNameIsInvalidUUID() throws Exception {
        setAuthName("not-a-uuid");

        mockMvc.perform(post("/api/follows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new FollowRequest(FOLLOWEE_ID))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(followService);
    }

    @Test
    @DisplayName("anonymousUser 프린시펄로 필수 인증 엔드포인트 요청 시 401 을 반환한다")
    void follow_fail_whenPrincipalIsAnonymousUser() throws Exception {
        setAuthName("anonymousUser");

        mockMvc.perform(delete("/api/follows/{followId}", FOLLOW_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(followService);
    }

    @Test
    @DisplayName("인증 정보가 authenticated=false 이면 필수 인증 엔드포인트는 401 을 반환한다")
    void follow_fail_whenAuthenticationNotAuthenticated() throws Exception {
        var auth = UsernamePasswordAuthenticationToken.unauthenticated(FOLLOWER_ID.toString(), null);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        mockMvc.perform(delete("/api/follows/{followId}", FOLLOW_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(followService);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void setAuth(UUID userId) {
        setAuthName(userId.toString());
    }

    private void setAuthName(String name) {
        var auth = new UsernamePasswordAuthenticationToken(name, null, List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }
}