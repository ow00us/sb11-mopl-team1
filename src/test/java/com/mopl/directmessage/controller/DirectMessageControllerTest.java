package com.mopl.directmessage.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.service.DirectMessageService;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.GlobalExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DirectMessageController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class DirectMessageControllerTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    private static final UUID REQUESTER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID RECEIVER_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID MESSAGE_ID =
        UUID.fromString(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        );

    private static final Instant CREATED_AT =
        Instant.parse("2026-07-30T03:00:00Z");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DirectMessageService directMessageService;

    @Test
    @DisplayName("DM 목록 조회 성공 시 200을 반환")
    void getDirectMessages_success() throws Exception {
        // given
        DirectMessageDto message =
            new DirectMessageDto(
                MESSAGE_ID,
                CONVERSATION_ID,
                CREATED_AT,
                new UserSummary(
                    REQUESTER_ID,
                    "발신자",
                    null
                ),
                new UserSummary(
                    RECEIVER_ID,
                    "수신자",
                    null
                ),
                "안녕하세요"
            );

        CursorResponse<DirectMessageDto> response =
            CursorResponse.of(
                List.of(message),
                null,
                null,
                false,
                1L,
                "createdAt",
                "DESCENDING"
            );

        when(
            directMessageService.getDirectMessages(
                REQUESTER_ID,
                CONVERSATION_ID,
                null,
                null,
                10,
                "DESCENDING",
                "createdAt"
            )
        ).thenReturn(response);

        // when & then
        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}/direct-messages",
                    CONVERSATION_ID
                )
                    .param("limit", "10")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param("sortBy", "createdAt")
                    .principal(
                        () -> REQUESTER_ID.toString()
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
                    .value(MESSAGE_ID.toString())
            )
            .andExpect(
                jsonPath("$.data[0].conversationId")
                    .value(CONVERSATION_ID.toString())
            )
            .andExpect(
                jsonPath("$.data[0].sender.userId")
                    .value(REQUESTER_ID.toString())
            )
            .andExpect(
                jsonPath("$.data[0].receiver.userId")
                    .value(RECEIVER_ID.toString())
            )
            .andExpect(
                jsonPath("$.data[0].content")
                    .value("안녕하세요")
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

        verify(directMessageService)
            .getDirectMessages(
                REQUESTER_ID,
                CONVERSATION_ID,
                null,
                null,
                10,
                "DESCENDING",
                "createdAt"
            );
    }

    @Test
    @DisplayName("인증 정보 없이 DM 목록 조회 시 401을 반환")
    void getDirectMessages_unauthenticated_returnsUnauthorized()
        throws Exception {

        // when & then
        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}/direct-messages",
                    CONVERSATION_ID
                )
                    .param("limit", "10")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(directMessageService);
    }

    @Test
    @DisplayName("limit이 1보다 작으면 400을 반환")
    void getDirectMessages_invalidLimit_returnsBadRequest()
        throws Exception {

        // when & then
        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}/direct-messages",
                    CONVERSATION_ID
                )
                    .param("limit", "0")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param("sortBy", "createdAt")
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest());

        verifyNoInteractions(directMessageService);
    }

    @Test
    @DisplayName("DM 읽음 처리 성공 시 200을 반환")
    void read_success() throws Exception {
        // when & then
        mockMvc.perform(
                post(
                    "/api/conversations/{conversationId}"
                        + "/direct-messages/{directMessageId}/read",
                    CONVERSATION_ID,
                    MESSAGE_ID
                )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isOk())
            .andExpect(content().string(""));

        verify(directMessageService).read(
            REQUESTER_ID,
            CONVERSATION_ID,
            MESSAGE_ID
        );
    }

    @Test
    @DisplayName("인증 정보 없이 DM 읽음 처리 시 401을 반환")
    void read_unauthenticated_returnsUnauthorized()
        throws Exception {

        // when & then
        mockMvc.perform(
                post(
                    "/api/conversations/{conversationId}"
                        + "/direct-messages/{directMessageId}/read",
                    CONVERSATION_ID,
                    MESSAGE_ID
                )
            )
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(directMessageService);
    }

    @Test
    @DisplayName("idAfter가 UUID 형식이 아니면 400을 반환한다")
    void getDirectMessages_invalidIdAfter_returnsBadRequest()
        throws Exception {

        UUID conversationId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();

        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}/direct-messages",
                    conversationId
                )
                    .principal(
                        () -> requesterId.toString()
                    )
                    .param("idAfter", "not-uuid")
                    .param("limit", "10")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );
    }

    @Test
    @DisplayName("limit이 누락되면 400을 반환한다")
    void getDirectMessages_missingLimit_returnsBadRequest()
        throws Exception {

        UUID conversationId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();

        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}/direct-messages",
                    conversationId
                )
                    .principal(
                        () -> requesterId.toString()
                    )
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );
    }

    @Test
    @DisplayName("sortDirection이 누락되면 400을 반환한다")
    void getDirectMessages_missingSortDirection_returnsBadRequest()
        throws Exception {

        UUID conversationId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();

        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}/direct-messages",
                    conversationId
                )
                    .principal(
                        () -> requesterId.toString()
                    )
                    .param("limit", "10")
                    .param("sortBy", "createdAt")
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );
    }

    @Test
    @DisplayName("sortBy가 누락되면 400을 반환한다")
    void getDirectMessages_missingSortBy_returnsBadRequest()
        throws Exception {

        UUID conversationId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();

        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}/direct-messages",
                    conversationId
                )
                    .principal(
                        () -> requesterId.toString()
                    )
                    .param("limit", "10")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );
    }
}
