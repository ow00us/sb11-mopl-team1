package com.mopl.playlist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.service.PlaylistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
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
    private static final Instant UPDATED_AT = Instant.parse("2026-07-27T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean PlaylistService playlistService;

    // ── POST /api/playlists ───────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    @DisplayName("플레이리스트 생성 성공 시 201과 PlaylistDto 를 반환한다")
    void create_success() throws Exception {
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
    @WithMockUser
    @DisplayName("title 이 빈 값이면 400 을 반환한다")
    void create_fail_blankTitle() throws Exception {
        mockMvc.perform(post("/api/playlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistCreateRequest("", "설명"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(playlistService);
    }

    // ── GET /api/playlists ────────────────────────────────────────────────────

    @Test
    @DisplayName("플레이리스트 목록 조회 시 CursorResponse 를 반환한다")
    void getList_success() throws Exception {
        CursorResponse<PlaylistDto> response = CursorResponse.of(
                List.of(sampleDto("제목", "설명")),
                null, null, false, 1L, "updatedAt", "ASCENDING");

        when(playlistService.getList(any(), any(), any(), any(), eq(10),
                eq("updatedAt"), eq("ASCENDING"))).thenReturn(response);

        mockMvc.perform(get("/api/playlists")
                        .param("limit", "10")
                        .param("sortBy", "updatedAt")
                        .param("sortDirection", "ASCENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(PLAYLIST_ID.toString()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    // ── GET /api/playlists/{playlistId} ───────────────────────────────────────

    @Test
    @DisplayName("플레이리스트 단건 조회 성공 시 200과 PlaylistDto 를 반환한다")
    void get_success() throws Exception {
        when(playlistService.get(PLAYLIST_ID)).thenReturn(sampleDto("제목", "설명"));

        mockMvc.perform(get("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PLAYLIST_ID.toString()))
                .andExpect(jsonPath("$.title").value("제목"));
    }

    @Test
    @DisplayName("존재하지 않는 플레이리스트 조회 시 404 를 반환한다")
    void get_fail_notFound() throws Exception {
        when(playlistService.get(PLAYLIST_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    // ── PATCH /api/playlists/{playlistId} ─────────────────────────────────────

    @Test
    @WithMockUser(username = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    @DisplayName("플레이리스트 수정 성공 시 200과 변경된 PlaylistDto 를 반환한다")
    void update_success() throws Exception {
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
    @WithMockUser(username = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    @DisplayName("소유자가 아닌 사용자가 수정 시 403 을 반환한다")
    void update_fail_forbidden() throws Exception {
        when(playlistService.update(eq(PLAYLIST_ID), any(), eq(OWNER_ID)))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(patch("/api/playlists/{playlistId}", PLAYLIST_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PlaylistUpdateRequest("제목", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    // ── DELETE /api/playlists/{playlistId} ────────────────────────────────────

    @Test
    @WithMockUser(username = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    @DisplayName("플레이리스트 삭제 성공 시 204 를 반환한다")
    void delete_success() throws Exception {
        doNothing().when(playlistService).delete(eq(PLAYLIST_ID), eq(OWNER_ID));

        mockMvc.perform(delete("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isNoContent());

        verify(playlistService).delete(PLAYLIST_ID, OWNER_ID);
    }

    @Test
    @WithMockUser(username = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    @DisplayName("소유자가 아닌 사용자가 삭제 시 403 을 반환한다")
    void delete_fail_forbidden() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(playlistService).delete(eq(PLAYLIST_ID), eq(OWNER_ID));

        mockMvc.perform(delete("/api/playlists/{playlistId}", PLAYLIST_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private PlaylistDto sampleDto(String title, String desc) {
        return new PlaylistDto(PLAYLIST_ID,
                new UserSummary(OWNER_ID, null, null),
                title, desc, UPDATED_AT, 0L, false, List.of());
    }
}
