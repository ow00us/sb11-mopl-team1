package com.mopl.playlist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.dto.SubscriberItemDto;
import com.mopl.playlist.service.PlaylistService;
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

@WebMvcTest(PlaylistController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlaylistControllerTest {

    private static final UUID PLAYLIST_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OWNER_ID    = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID    = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-27T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean PlaylistService playlistService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── POST /api/playlists ───────────────────────────────────────────────────

    @Test
    @DisplayName("플레이리스트 생성 성공 시 201과 PlaylistDto 를 반환한다")
    void create_success() throws Exception {
        setAuth(OWNER_ID);
        PlaylistDto response = sampleDto("내 플레이리스트", "설명");
        when(playlistService.create(any(), eq(OWNER_ID))).thenReturn(response);

        mockMvc.perform(post("/api/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistCreateRequest("내 플레이리스트", "설명"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(PLAYLIST_ID.toString()))
                .andExpect(jsonPath("$.title").value("내 플레이리스트"))
                .andExpect(jsonPath("$.owner.userId").value(OWNER_ID.toString()));
    }

    @Test
    @DisplayName("title 이 빈 값이면 400 을 반환한다")
    void create_fail_blankTitle() throws Exception {
        setAuth(OWNER_ID);

        mockMvc.perform(post("/api/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistCreateRequest("", "설명"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(playlistService);
    }

    @Test
    @DisplayName("미인증 사용자가 생성 시도 시 401 을 반환한다")
    void create_fail_unauthorized() throws Exception {
        mockMvc.perform(post("/api/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistCreateRequest("제목", "설명"))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService);
    }

    // ── GET /api/playlists ────────────────────────────────────────────────────

    @Test
    @DisplayName("플레이리스트 목록 조회 시 CursorResponse 를 반환한다")
    void getList_success() throws Exception {
        CursorResponse<PlaylistDto> response = CursorResponse.of(
                List.of(sampleDto("제목", "설명")),
                null, null, false, 1L, "updatedAt", "ASCENDING");

        when(playlistService.getList(any(), any(), any(), any(), any(), eq(10),
                eq("updatedAt"), eq("ASCENDING"), any())).thenReturn(response);

        mockMvc.perform(get("/api/playlists")
                        .param("limit", "10")
                        .param("sortBy", "updatedAt")
                        .param("sortDirection", "ASCENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(PLAYLIST_ID.toString()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("limit 이 0 이하면 400 을 반환한다")
    void getList_fail_invalidLimit() throws Exception {
        mockMvc.perform(get("/api/playlists")
                        .param("limit", "0")
                        .param("sortBy", "updatedAt")
                        .param("sortDirection", "ASCENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("limit 이 100 초과면 400 을 반환한다")
    void getList_fail_limitExceedsMax() throws Exception {
        mockMvc.perform(get("/api/playlists")
                        .param("limit", "101")
                        .param("sortBy", "updatedAt")
                        .param("sortDirection", "ASCENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sortBy 가 허용값이 아니면 400 을 반환한다")
    void getList_fail_invalidSortBy() throws Exception {
        mockMvc.perform(get("/api/playlists")
                        .param("limit", "10")
                        .param("sortBy", "invalidField")
                        .param("sortDirection", "ASCENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sortDirection 이 허용값이 아니면 400 을 반환한다")
    void getList_fail_invalidSortDirection() throws Exception {
        mockMvc.perform(get("/api/playlists")
                        .param("limit", "10")
                        .param("sortBy", "updatedAt")
                        .param("sortDirection", "WRONG"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/playlists/{playlistId} ───────────────────────────────────────

    @Test
    @DisplayName("플레이리스트 단건 조회 성공 시 200과 PlaylistDto 를 반환한다")
    void get_success() throws Exception {
        when(playlistService.get(eq(PLAYLIST_ID), any())).thenReturn(sampleDto("제목", "설명"));

        mockMvc.perform(get("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PLAYLIST_ID.toString()))
                .andExpect(jsonPath("$.title").value("제목"));
    }

    @Test
    @DisplayName("존재하지 않는 플레이리스트 조회 시 404 를 반환한다")
    void get_fail_notFound() throws Exception {
        when(playlistService.get(eq(PLAYLIST_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    // ── PATCH /api/playlists/{playlistId} ─────────────────────────────────────

    @Test
    @DisplayName("플레이리스트 수정 성공 시 200과 변경된 PlaylistDto 를 반환한다")
    void update_success() throws Exception {
        setAuth(OWNER_ID);
        PlaylistDto updated = sampleDto("새 제목", "설명");
        when(playlistService.update(eq(PLAYLIST_ID), any(), eq(OWNER_ID))).thenReturn(updated);

        mockMvc.perform(patch("/api/playlists/{playlistId}", PLAYLIST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistUpdateRequest("새 제목", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("새 제목"));
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 수정 시 403 을 반환한다")
    void update_fail_forbidden() throws Exception {
        setAuth(OWNER_ID);
        when(playlistService.update(eq(PLAYLIST_ID), any(), eq(OWNER_ID)))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(patch("/api/playlists/{playlistId}", PLAYLIST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistUpdateRequest("제목", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    @Test
    @DisplayName("미인증 사용자가 수정 시도 시 401 을 반환한다")
    void update_fail_unauthorized() throws Exception {
        mockMvc.perform(patch("/api/playlists/{playlistId}", PLAYLIST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistUpdateRequest("제목", null))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService);
    }

    @Test
    @DisplayName("title 이 255자를 초과하면 400 을 반환한다 (공백 문자열이어도 @Size 가 먼저 적용됨)")
    void update_fail_titleTooLong() throws Exception {
        setAuth(OWNER_ID);
        // 공백만 있어도 256자 이상이면 update()의 isBlank() 무시 로직에 도달하기 전에
        // @Size(max = 255) 검증이 먼저 실패해 400 이 반환된다.
        String tooLong = " ".repeat(256);

        mockMvc.perform(patch("/api/playlists/{playlistId}", PLAYLIST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistUpdateRequest(tooLong, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(playlistService);
    }

    // ── DELETE /api/playlists/{playlistId} ────────────────────────────────────

    @Test
    @DisplayName("플레이리스트 삭제 성공 시 204 를 반환한다")
    void delete_success() throws Exception {
        setAuth(OWNER_ID);
        doNothing().when(playlistService).delete(eq(PLAYLIST_ID), eq(OWNER_ID));

        mockMvc.perform(delete("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isNoContent());

        verify(playlistService).delete(PLAYLIST_ID, OWNER_ID);
    }

    @Test
    @DisplayName("소유자가 아닌 사용자가 삭제 시 403 을 반환한다")
    void delete_fail_forbidden() throws Exception {
        setAuth(OWNER_ID);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(playlistService).delete(eq(PLAYLIST_ID), eq(OWNER_ID));

        mockMvc.perform(delete("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    @Test
    @DisplayName("미인증 사용자가 삭제 시도 시 401 을 반환한다")
    void delete_fail_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService);
    }

    // ── POST /api/playlists/{playlistId}/subscription ─────────────────────────

    @Test
    @DisplayName("구독 성공 시 204 를 반환한다")
    void subscribe_success() throws Exception {
        setAuth(OTHER_ID);
        doNothing().when(playlistService).subscribe(PLAYLIST_ID, OTHER_ID);

        mockMvc.perform(post("/api/playlists/{playlistId}/subscription", PLAYLIST_ID))
                .andExpect(status().isNoContent());

        verify(playlistService).subscribe(PLAYLIST_ID, OTHER_ID);
    }

    @Test
    @DisplayName("소유자 본인이 구독 시도 시 403 을 반환한다")
    void subscribe_fail_owner() throws Exception {
        setAuth(OWNER_ID);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(playlistService).subscribe(PLAYLIST_ID, OWNER_ID);

        mockMvc.perform(post("/api/playlists/{playlistId}/subscription", PLAYLIST_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    @Test
    @DisplayName("중복 구독 시도 시에도 204 를 반환한다 (ADR 2 계약, 서비스는 no-op)")
    void subscribe_duplicate_returns204() throws Exception {
        setAuth(OTHER_ID);
        doNothing().when(playlistService).subscribe(PLAYLIST_ID, OTHER_ID);

        mockMvc.perform(post("/api/playlists/{playlistId}/subscription", PLAYLIST_ID))
                .andExpect(status().isNoContent());

        verify(playlistService).subscribe(PLAYLIST_ID, OTHER_ID);
    }

    @Test
    @DisplayName("미인증 상태에서 구독 시도 시 401 을 반환한다")
    void subscribe_fail_unauthorized() throws Exception {
        mockMvc.perform(post("/api/playlists/{playlistId}/subscription", PLAYLIST_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /api/playlists/{playlistId}/subscription ───────────────────────

    @Test
    @DisplayName("구독 취소 성공 시 204 를 반환한다")
    void unsubscribe_success() throws Exception {
        setAuth(OTHER_ID);
        doNothing().when(playlistService).unsubscribe(PLAYLIST_ID, OTHER_ID);

        mockMvc.perform(delete("/api/playlists/{playlistId}/subscription", PLAYLIST_ID))
                .andExpect(status().isNoContent());

        verify(playlistService).unsubscribe(PLAYLIST_ID, OTHER_ID);
    }

    @Test
    @DisplayName("구독하지 않은 플레이리스트 취소 시 404 를 반환한다")
    void unsubscribe_fail_notSubscribed() throws Exception {
        setAuth(OTHER_ID);
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(playlistService).unsubscribe(PLAYLIST_ID, OTHER_ID);

        mockMvc.perform(delete("/api/playlists/{playlistId}/subscription", PLAYLIST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    @Test
    @DisplayName("미인증 상태에서 구독 취소 시도 시 401 을 반환한다")
    void unsubscribe_fail_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/playlists/{playlistId}/subscription", PLAYLIST_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/playlists/{playlistId}/subscribers ────────────────────────────

    @Test
    @DisplayName("구독자 목록 조회 성공 시 200과 CursorResponse 를 반환한다")
    void getSubscribers_success() throws Exception {
        setAuth(OTHER_ID);
        Instant now = Instant.parse("2026-08-01T10:00:00Z");
        UUID subscriptionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        CursorResponse<SubscriberItemDto> response = CursorResponse.of(
                List.of(new SubscriberItemDto(subscriptionId, new UserSummary(OTHER_ID, null, null), now)),
                null, null, false, 1L, "subscribedAt", "DESCENDING");
        when(playlistService.getSubscribers(eq(PLAYLIST_ID), any(), any(), eq(10),
                eq("subscribedAt"), eq("DESCENDING"))).thenReturn(response);

        mockMvc.perform(get("/api/playlists/{playlistId}/subscribers", PLAYLIST_ID)
                        .param("limit", "10")
                        .param("sortBy", "subscribedAt")
                        .param("sortDirection", "DESCENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subscriptionId").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.data[0].user.userId").value(OTHER_ID.toString()))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("존재하지 않는 플레이리스트의 구독자 조회 시 404 를 반환한다")
    void getSubscribers_fail_notFound() throws Exception {
        setAuth(OTHER_ID);
        when(playlistService.getSubscribers(eq(PLAYLIST_ID), any(), any(), eq(10),
                any(), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/playlists/{playlistId}/subscribers", PLAYLIST_ID)
                        .param("limit", "10"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    @Test
    @DisplayName("구독자 목록 조회 시 limit 이 0 이하면 400 을 반환한다")
    void getSubscribers_fail_invalidLimit() throws Exception {
        mockMvc.perform(get("/api/playlists/{playlistId}/subscribers", PLAYLIST_ID)
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("구독자 목록 조회 시 limit 이 100 초과면 400 을 반환한다")
    void getSubscribers_fail_limitExceedsMax() throws Exception {
        mockMvc.perform(get("/api/playlists/{playlistId}/subscribers", PLAYLIST_ID)
                        .param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("구독자 목록 조회 시 sortDirection 이 허용값이 아니면 400 을 반환한다")
    void getSubscribers_fail_invalidSortDirection() throws Exception {
        mockMvc.perform(get("/api/playlists/{playlistId}/subscribers", PLAYLIST_ID)
                        .param("limit", "10")
                        .param("sortDirection", "WRONG"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("구독자 목록 조회 시 sortDirection=ASCENDING 은 현재 미지원이므로 400 과 INVALID_INPUT 을 반환한다")
    void getSubscribers_fail_ascendingNotSupported() throws Exception {
        mockMvc.perform(get("/api/playlists/{playlistId}/subscribers", PLAYLIST_ID)
                        .param("limit", "10")
                        .param("sortDirection", "ASCENDING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));
    }

    @Test
    @DisplayName("미인증 상태에서 구독자 목록 조회 시 401 을 반환한다")
    void getSubscribers_fail_unauthorized() throws Exception {
        mockMvc.perform(get("/api/playlists/{playlistId}/subscribers", PLAYLIST_ID)
                        .param("limit", "10"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(playlistService);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void setAuth(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    private PlaylistDto sampleDto(String title, String desc) {
        return new PlaylistDto(PLAYLIST_ID,
                new UserSummary(OWNER_ID, null, null),
                title, desc, UPDATED_AT, 0L, false, List.of());
    }
}