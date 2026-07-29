package com.mopl.watchingsession.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.UserSummary;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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

    @Test
    @DisplayName("시청 중인 콘텐츠가 있으면 200과 세션 정보 반환")
    void get_success_whenWatching() throws Exception {
        // given
        WatchingSessionDto response = new WatchingSessionDto(
            UUID.randomUUID(),
            new UserSummary(WATCHER_ID, null, null),
            new ContentSummary(CONTENT_ID, null, null, null, null, List.of(), 0.0, 0),
            Instant.parse("2026-07-28T03:00:00Z"));  when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(response));

            // when & then
        mockMvc.perform(get("/api/users/{watcherId}/watching-sessions", WATCHER_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.id").value(CONTENT_ID.toString()));
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

    @Test
    @DisplayName("만료된 세션은 204 반환")
    void get_success_whenExpired() throws Exception {
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/users/{watcherId}/watching-sessions", WATCHER_ID))
            .andExpect(status().isNoContent());
    }

}
