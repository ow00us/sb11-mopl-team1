package com.mopl.playlist.controller;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.playlist.service.PlaylistService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlaylistController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class PlaylistContentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean PlaylistService playlistService;

    private static final UUID PLAYLIST_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CONTENT_ID  = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final String USER_ID   = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    // ── POST /api/playlists/{playlistId}/contents/{contentId} ─────────────────

    @Test
    @WithMockUser(username = USER_ID)
    @DisplayName("콘텐츠 추가 성공 시 204를 반환한다")
    void addContent_success() throws Exception {
        doNothing().when(playlistService).addContent(PLAYLIST_ID, CONTENT_ID, UUID.fromString(USER_ID));

        mockMvc.perform(post("/api/playlists/{playlistId}/contents/{contentId}", PLAYLIST_ID, CONTENT_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = USER_ID)
    @DisplayName("소유자가 아닌 사용자가 콘텐츠를 추가하면 403을 반환한다")
    void addContent_forbidden() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(playlistService).addContent(PLAYLIST_ID, CONTENT_ID, UUID.fromString(USER_ID));

        mockMvc.perform(post("/api/playlists/{playlistId}/contents/{contentId}", PLAYLIST_ID, CONTENT_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 사용자가 콘텐츠를 추가하면 401을 반환한다")
    void addContent_unauthorized() throws Exception {
        mockMvc.perform(post("/api/playlists/{playlistId}/contents/{contentId}", PLAYLIST_ID, CONTENT_ID))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /api/playlists/{playlistId}/contents/{contentId} ──────────────

    @Test
    @WithMockUser(username = USER_ID)
    @DisplayName("콘텐츠 삭제 성공 시 204를 반환한다")
    void removeContent_success() throws Exception {
        doNothing().when(playlistService).removeContent(PLAYLIST_ID, CONTENT_ID, UUID.fromString(USER_ID));

        mockMvc.perform(delete("/api/playlists/{playlistId}/contents/{contentId}", PLAYLIST_ID, CONTENT_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = USER_ID)
    @DisplayName("소유자가 아닌 사용자가 콘텐츠를 삭제하면 403을 반환한다")
    void removeContent_forbidden() throws Exception {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(playlistService).removeContent(PLAYLIST_ID, CONTENT_ID, UUID.fromString(USER_ID));

        mockMvc.perform(delete("/api/playlists/{playlistId}/contents/{contentId}", PLAYLIST_ID, CONTENT_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 사용자가 콘텐츠를 삭제하면 401을 반환한다")
    void removeContent_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/playlists/{playlistId}/contents/{contentId}", PLAYLIST_ID, CONTENT_ID))
                .andExpect(status().isUnauthorized());
    }
}