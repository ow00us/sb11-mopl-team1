package com.mopl.notification.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.global.common.CursorResponse;
import com.mopl.notification.dto.NotificationDto;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.service.NotificationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NotificationControllerTest {

    private static final UUID RECEIVER_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID NOTIFICATION_ID = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    private static final Instant CREATED_AT =
        Instant.parse("2026-07-28T03:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationService notificationService;

    @Test
    @DisplayName("읽지 않은 알림 목록 조회 성공 시 200을 반환")
    void getNotifications_success() throws Exception {
        // given
        NotificationDto notification = new NotificationDto(
            NOTIFICATION_ID,
            CREATED_AT,
            RECEIVER_ID,
            "새로운 알림",
            "알림 내용",
            NotificationLevel.INFO
        );

        CursorResponse<NotificationDto> response =
            CursorResponse.of(
                List.of(notification),
                null,
                null,
                false,
                1L,
                "createdAt",
                "DESCENDING"
            );

        when(
            notificationService.getUnreadNotifications(
                RECEIVER_ID,
                null,
                null,
                10,
                "DESCENDING",
                "createdAt"
            )
        ).thenReturn(response);

        // when & then
        mockMvc.perform(
                get("/api/notifications")
                    .param("limit", "10")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param("sortBy", "createdAt")
                    .principal(
                        () -> RECEIVER_ID.toString()
                    )
            )
            .andExpect(status().isOk())
            .andExpect(
                content().contentType(
                    "application/json"
                )
            )
            .andExpect(
                jsonPath("$.data[0].id")
                    .value(NOTIFICATION_ID.toString())
            )
            .andExpect(
                jsonPath("$.data[0].receiverId")
                    .value(RECEIVER_ID.toString())
            )
            .andExpect(
                jsonPath("$.data[0].title")
                    .value("새로운 알림")
            )
            .andExpect(
                jsonPath("$.data[0].level")
                    .value("INFO")
            )
            .andExpect(
                jsonPath("$.hasNext")
                    .value(false)
            )
            .andExpect(
                jsonPath("$.totalCount")
                    .value(1)
            )
            .andExpect(
                jsonPath("$.sortBy")
                    .value("createdAt")
            )
            .andExpect(
                jsonPath("$.sortDirection")
                    .value("DESCENDING")
            );

        verify(notificationService)
            .getUnreadNotifications(
                RECEIVER_ID,
                null,
                null,
                10,
                "DESCENDING",
                "createdAt"
            );
    }

    @Test
    @DisplayName("알림 읽음 처리 성공 시 204를 반환")
    void read_success() throws Exception {
        // when & then
        mockMvc.perform(
                delete(
                    "/api/notifications/{notificationId}",
                    NOTIFICATION_ID
                )
                    .principal(
                        () -> RECEIVER_ID.toString()
                    )
            )
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(notificationService).read(
            NOTIFICATION_ID,
            RECEIVER_ID
        );
    }

    @Test
    @DisplayName("인증 정보 없이 알림 목록 조회 시 401을 반환")
    void getNotifications_unauthenticated_returnsUnauthorized()
        throws Exception {

        // when & then
        mockMvc.perform(
                get("/api/notifications")
                    .param("limit", "10")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        verifyNoInteractions(notificationService);
    }
}
