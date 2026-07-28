package com.mopl.watchingsession.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WatchingSessionController.class)
@AutoConfigureMockMvc(addFilters = false)
public class WatchingSessionControllerTest {

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WatchingSessionService watchingSessionService;

    // 실제 JWT 인증 필터는 addFilters=false로 끄고 요청에 principal만 직접 심어서 로직만 검증
    private Authentication authenticationOf(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), null);
    }

    @Test
    @DisplayName("시청 시작 성공 시 201과 세션 정보 반환")
    void start_success() throws Exception {
        // given
        WatchingSessionDto response = new WatchingSessionDto(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, Instant.parse("2026-07-28T03:00:00Z"));
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID)).thenReturn(response);

        // when & then
        mockMvc.perform(post("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
            .principal(authenticationOf(WATCHER_ID)))
            .andExpect(status().isCreated())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.watcherId").value(WATCHER_ID.toString()))
            .andExpect(jsonPath("$.contentId").value(CONTENT_ID.toString()));
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠로 시작하면 404 CONTENT_404_1을 반환")
    void start_fail_whenContentNotFound() throws Exception {
        // given
        when(watchingSessionService.start(any(), any()))
            .thenThrow(new BusinessException(ErrorCode.CONTENT_NOT_FOUND));

        // when & then
        mockMvc.perform(post("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
            .principal(authenticationOf(WATCHER_ID)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("CONTENT_404_1"));
    }

    @Test
    @DisplayName("시청 종료 성공 시 204 반환")
    void end_success() throws Exception {
        // when & then
        mockMvc.perform(delete("/api/watching-sessions/me")
            .principal(authenticationOf(WATCHER_ID)))
            .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("시청 중인 콘텐츠가 있으면 200과 세션 정보 반환")
    void get_success_whenWatching() throws Exception {
        // given
        WatchingSessionDto response = new WatchingSessionDto(
            UUID.randomUUID(), WATCHER_ID, CONTENT_ID, Instant.parse("2026-07-28T03:00:00Z"));
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(response));

        // when & then
        mockMvc.perform(get("/api/users/{watcherId}/watching-sessions", WATCHER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contentId").value(CONTENT_ID.toString()));
    }

    @Test
    @DisplayName("시청 중이 아니면 204 반환")
    void get_success_whenNotWatching() throws Exception {
        // given
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.empty());

        // when & then
        mockMvc.perform(get("/api/users/{watcherId}/watching-sessions", WATCHER_ID))
            .andExpect(status().isNoContent());
    }

}
