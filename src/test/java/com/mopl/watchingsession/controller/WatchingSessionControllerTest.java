package com.mopl.watchingsession.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.GlobalExceptionHandler;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WatchingSessionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
public class WatchingSessionControllerTest {

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WatchingSessionService watchingSessionService;

    /* GET /api/users/{watcherId}/watching-sessions */

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

    /* GET /api/contents/{contentId}/watching-sessions */

    @Test
    @DisplayName("콘텐츠별 목록 조회 성공 시 200과 CursorResponse 반환")
    void getListByContent_success() throws Exception {
        // given
        WatchingSessionDto dto = new WatchingSessionDto(
            UUID.randomUUID(),
            new UserSummary(WATCHER_ID, "김철수", null),
            new ContentSummary(CONTENT_ID, "movie", null, null, null, List.of(), 0.0, 0),
            Instant.parse("2026-07-28T03:00:00Z"));

        CursorResponse<WatchingSessionDto> response = CursorResponse.of(
            List.of(dto), null, null, false, 1L, "createdAt", "DESCENDING"
        );

        when(watchingSessionService.getListByContent(
            eq(CONTENT_ID), isNull(), isNull(), isNull(), eq(10), eq("createdAt"), eq("DESCENDING")))
            .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("limit", "10")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(dto.id().toString()))
            .andExpect(jsonPath("$.totalCount").value(1));
    }

    @Test
    @DisplayName("watcherNameLike 파라미터가 서비스로 전달됨")
    void getListByContent_passesWatcherNameLikeParam() throws Exception {
        // given
        CursorResponse<WatchingSessionDto> response = CursorResponse.of(
            List.of(), null, null, false, 0L, "createdAt", "DESCENDING"
        );
        when(watchingSessionService.getListByContent(
            eq(CONTENT_ID), eq("김"), isNull(), isNull(), eq(10), eq("createdAt"), eq("DESCENDING")))
            .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("watcherNameLike", "김")
                .param("limit", "10")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isOk());

        verify(watchingSessionService).getListByContent(
            eq(CONTENT_ID), eq("김"), isNull(), isNull(), eq(10), eq("createdAt"), eq("DESCENDING"));
    }

    @Test
    @DisplayName("cursor와 idAfter가 함께 전달")
    void getListByContent_passesCursorAndIdAfter() throws Exception {
        // given
        UUID idAfter = UUID.randomUUID();
        CursorResponse<WatchingSessionDto> response = CursorResponse.of(
            List.of(), null, null, false, 0L, "createdAt", "DESCENDING"
        );
        when(watchingSessionService.getListByContent(
            eq(CONTENT_ID), isNull(), eq("cursorValue"), eq(idAfter), eq(10), eq("createdAt"), eq("DESCENDING")))
            .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("cursor", "cursorValue")
                .param("idAfter", idAfter.toString())
                .param("limit", "10")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("서비스에서 CONTENT_NOT_FOUND 던지면 404 반환")
    void getListByContent_fail_contentNotFound() throws Exception {
        // given
        when(watchingSessionService.getListByContent(
            eq(CONTENT_ID), isNull(), isNull(), isNull(), eq(10), eq("createdAt"), eq("DESCENDING")))
            .thenThrow(new BusinessException(ErrorCode.CONTENT_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("limit", "10")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("서비스에서 INVALID_INPUT 던지면 400 반환")
    void getListByContent_fail_invalidInput() throws Exception {
        // given
        when(watchingSessionService.getListByContent(
            eq(CONTENT_ID), isNull(), eq("cursorOnly"), isNull(), eq(10), eq("createdAt"), eq("DESCENDING")))
            .thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));

        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("cursor", "cursorOnly")
                .param("limit", "10")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("필수 파라미터(sortBy) 누락 시 400 반환")
    void getListByContent_fail_missingSortBy() throws Exception {
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("limit", "10")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("필수 파라미터(sortDirection) 누락 시 400 반환")
    void getListByContent_fail_missingSortDirection() throws Exception {
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("limit", "10")
                .param("sortBy", "createdAt"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("필수 파라미터(limit) 누락 시 400 반환")
    void getListByContent_fail_missingRequiredParam() throws Exception {
        // when & then - limit 없이 요청
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("limit이 1보다 작으면 400을 반환")
    void getListByContent_fail_limitLessThanOne() throws Exception {
        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("limit", "0")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(watchingSessionService);
    }

    @Test
    @DisplayName("limit이 100보다 크면 400을 반환")
    void getListByContent_fail_limitGreaterThanOneHundred() throws Exception {
        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("limit", "101")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(watchingSessionService);
    }

    @Test
    @DisplayName("limit이 1이면 성공 (최솟값)")
    void getListByContent_success_limitAtLowerBound() throws Exception {
        // given
        CursorResponse<WatchingSessionDto> response = CursorResponse.of(
            List.of(), null, null, false, 0L, "createdAt", "DESCENDING"
        );
        when(watchingSessionService.getListByContent(
            eq(CONTENT_ID), isNull(), isNull(), isNull(), eq(1), eq("createdAt"), eq("DESCENDING")))
            .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("limit", "1")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("limit이 100이면 성공 (최댓값)")
    void getListByContent_success_limitAtUpperBound() throws Exception {
        // given
        CursorResponse<WatchingSessionDto> response = CursorResponse.of(
            List.of(), null, null, false, 0L, "createdAt", "DESCENDING"
        );
        when(watchingSessionService.getListByContent(
            eq(CONTENT_ID), isNull(), isNull(), isNull(), eq(100), eq("createdAt"), eq("DESCENDING")))
            .thenReturn(response);

        // when & then
        mockMvc.perform(get("/api/contents/{contentId}/watching-sessions", CONTENT_ID)
                .param("limit", "100")
                .param("sortBy", "createdAt")
                .param("sortDirection", "DESCENDING"))
            .andExpect(status().isOk());
    }
}
