package com.mopl.directmessage.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mopl.directmessage.dto.ConversationCreateRequest;
import com.mopl.directmessage.dto.ConversationCreateResult;
import com.mopl.directmessage.dto.ConversationDto;
import com.mopl.directmessage.service.ConversationService;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
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

@WebMvcTest(ConversationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ConversationControllerTest {

    private static final UUID REQUESTER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID WITH_USER_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ConversationService conversationService;

    @Test
    @DisplayName("새 대화 생성 성공 시 201을 반환")
    void create_newConversation_returnsCreated()
        throws Exception {

        // given
        ConversationDto response =
            createConversationDto();

        when(
            conversationService.create(
                eq(REQUESTER_ID),
                any(ConversationCreateRequest.class)
            )
        ).thenReturn(
            new ConversationCreateResult(
                response,
                true
            )
        );

        // when & then
        mockMvc.perform(
                post("/api/conversations")
                    .contentType("application/json")
                    .content(
                        """
                        {
                          "withUserId": "%s"
                        }
                        """.formatted(WITH_USER_ID)
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isCreated())
            .andExpect(
                content().contentType(
                    "application/json"
                )
            )
            .andExpect(
                jsonPath("$.id")
                    .value(
                        CONVERSATION_ID.toString()
                    )
            )
            .andExpect(
                jsonPath("$.with.userId")
                    .value(
                        WITH_USER_ID.toString()
                    )
            )
            .andExpect(
                jsonPath("$.with.name")
                    .value("상대 사용자")
            )
            .andExpect(
                jsonPath("$.latestMessage")
                    .value(nullValue())
            )
            .andExpect(
                jsonPath("$.hasUnread")
                    .value(false)
            );

        verify(conversationService)
            .create(
                eq(REQUESTER_ID),
                any(ConversationCreateRequest.class)
            );
    }

    @Test
    @DisplayName("기존 대화가 있으면 200을 반환")
    void create_existingConversation_returnsOk()
        throws Exception {

        // given
        ConversationDto response =
            createConversationDto();

        when(
            conversationService.create(
                eq(REQUESTER_ID),
                any(ConversationCreateRequest.class)
            )
        ).thenReturn(
            new ConversationCreateResult(
                response,
                false
            )
        );

        // when & then
        mockMvc.perform(
                post("/api/conversations")
                    .contentType("application/json")
                    .content(
                        """
                        {
                          "withUserId": "%s"
                        }
                        """.formatted(WITH_USER_ID)
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.id")
                    .value(
                        CONVERSATION_ID.toString()
                    )
            )
            .andExpect(
                jsonPath("$.with.userId")
                    .value(
                        WITH_USER_ID.toString()
                    )
            )
            .andExpect(
                jsonPath("$.hasUnread")
                    .value(false)
            );

        verify(conversationService)
            .create(
                eq(REQUESTER_ID),
                any(ConversationCreateRequest.class)
            );
    }

    @Test
    @DisplayName("withUserId가 누락되면 400을 반환")
    void create_missingWithUserId_returnsBadRequest()
        throws Exception {

        // when & then
        mockMvc.perform(
                post("/api/conversations")
                    .contentType("application/json")
                    .content("{}")
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("withUserId가 UUID 형식이 아니면 400을 반환")
    void create_invalidWithUserId_returnsBadRequest()
        throws Exception {

        // when & then
        mockMvc.perform(
                post("/api/conversations")
                    .contentType("application/json")
                    .content(
                        """
                        {
                          "withUserId": "not-uuid"
                        }
                        """
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("상대 사용자가 존재하지 않으면 404를 반환")
    void create_userNotFound_returnsNotFound()
        throws Exception {

        // given
        when(
            conversationService.create(
                eq(REQUESTER_ID),
                any(ConversationCreateRequest.class)
            )
        ).thenThrow(
            new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            )
        );

        // when & then
        mockMvc.perform(
                post("/api/conversations")
                    .contentType("application/json")
                    .content(
                        """
                        {
                          "withUserId": "%s"
                        }
                        """.formatted(WITH_USER_ID)
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isNotFound())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_404_1")
            );
    }

    @Test
    @DisplayName("인증 정보 없이 대화를 생성하면 401을 반환")
    void create_unauthenticated_returnsUnauthorized()
        throws Exception {

        // when & then
        mockMvc.perform(
                post("/api/conversations")
                    .contentType("application/json")
                    .content(
                        """
                        {
                          "withUserId": "%s"
                        }
                        """.formatted(WITH_USER_ID)
                    )
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    private ConversationDto createConversationDto() {
        UserSummary withUser =
            new UserSummary(
                WITH_USER_ID,
                "상대 사용자",
                null
            );

        return new ConversationDto(
            CONVERSATION_ID,
            withUser,
            null,
            false
        );
    }

    @Test
    @DisplayName("대화 참여자가 대화를 조회하면 200을 반환")
    void getConversation_success_returnsOk()
        throws Exception {

        // given
        ConversationDto response =
            createConversationDto();

        when(
            conversationService.getConversation(
                REQUESTER_ID,
                CONVERSATION_ID
            )
        ).thenReturn(response);

        // when & then
        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}",
                    CONVERSATION_ID
                )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.id")
                    .value(CONVERSATION_ID.toString())
            )
            .andExpect(
                jsonPath("$.with.userId")
                    .value(WITH_USER_ID.toString())
            )
            .andExpect(
                jsonPath("$.latestMessage")
                    .value(nullValue())
            )
            .andExpect(
                jsonPath("$.hasUnread")
                    .value(false)
            );

        verify(conversationService)
            .getConversation(
                REQUESTER_ID,
                CONVERSATION_ID
            );
    }

    @Test
    @DisplayName("특정 사용자와의 대화를 조회하면 200을 반환")
    void getConversationWithUser_success_returnsOk()
        throws Exception {

        // given
        ConversationDto response =
            createConversationDto();

        when(
            conversationService
                .getConversationWithUser(
                    REQUESTER_ID,
                    WITH_USER_ID
                )
        ).thenReturn(response);

        // when & then
        mockMvc.perform(
                get("/api/conversations/with")
                    .param(
                        "userId",
                        WITH_USER_ID.toString()
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.id")
                    .value(CONVERSATION_ID.toString())
            )
            .andExpect(
                jsonPath("$.with.userId")
                    .value(WITH_USER_ID.toString())
            )
            .andExpect(
                jsonPath("$.latestMessage")
                    .value(nullValue())
            )
            .andExpect(
                jsonPath("$.hasUnread")
                    .value(false)
            );

        verify(conversationService)
            .getConversationWithUser(
                REQUESTER_ID,
                WITH_USER_ID
            );
    }

    @Test
    @DisplayName("대화를 찾을 수 없으면 404를 반환")
    void getConversation_notFound_returnsNotFound()
        throws Exception {

        // given
        when(
            conversationService.getConversation(
                REQUESTER_ID,
                CONVERSATION_ID
            )
        ).thenThrow(
            new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            )
        );

        // when & then
        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}",
                    CONVERSATION_ID
                )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isNotFound())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_404_1")
            );
    }

    @Test
    @DisplayName("특정 사용자와의 대화가 없으면 404를 반환")
    void getConversationWithUser_notFound_returnsNotFound()
        throws Exception {

        // given
        when(
            conversationService
                .getConversationWithUser(
                    REQUESTER_ID,
                    WITH_USER_ID
                )
        ).thenThrow(
            new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            )
        );

        // when & then
        mockMvc.perform(
                get("/api/conversations/with")
                    .param(
                        "userId",
                        WITH_USER_ID.toString()
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isNotFound())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_404_1")
            );
    }

    @Test
    @DisplayName("conversationId가 UUID 형식이 아니면 400을 반환")
    void getConversation_invalidConversationId_returnsBadRequest()
        throws Exception {

        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}",
                    "not-uuid"
                )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("특정 사용자 조회에서 userId가 누락되면 400을 반환")
    void getConversationWithUser_missingUserId_returnsBadRequest()
        throws Exception {

        mockMvc.perform(
                get("/api/conversations/with")
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("userId가 UUID 형식이 아니면 400을 반환")
    void getConversationWithUser_invalidUserId_returnsBadRequest()
        throws Exception {

        mockMvc.perform(
                get("/api/conversations/with")
                    .param(
                        "userId",
                        "not-uuid"
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("인증 정보 없이 대화를 조회하면 401을 반환")
    void getConversation_unauthenticated_returnsUnauthorized()
        throws Exception {

        mockMvc.perform(
                get(
                    "/api/conversations/{conversationId}",
                    CONVERSATION_ID
                )
            )
            .andExpect(status().isUnauthorized())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_401_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("대화 목록 조회 성공 시 커서 응답을 반환")
    void getConversations_success_returnsOk()
        throws Exception {

        // given
        Instant createdAt =
            Instant.parse("2026-08-01T01:00:00Z");

        ConversationDto conversation =
            createConversationDto();

        CursorResponse<ConversationDto> response =
            CursorResponse.of(
                List.of(conversation),
                createdAt.toString(),
                CONVERSATION_ID,
                true,
                2L,
                "createdAt",
                "DESCENDING"
            );

        when(
            conversationService.getConversations(
                eq(REQUESTER_ID),
                eq("상대"),
                isNull(),
                isNull(),
                eq(1),
                eq("DESCENDING"),
                eq("createdAt")
            )
        ).thenReturn(response);

        // when & then
        mockMvc.perform(
                get("/api/conversations")
                    .param("keywordLike", "상대")
                    .param("limit", "1")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param(
                        "sortBy",
                        "createdAt"
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.data[0].id")
                    .value(
                        CONVERSATION_ID.toString()
                    )
            )
            .andExpect(
                jsonPath("$.data[0].with.userId")
                    .value(
                        WITH_USER_ID.toString()
                    )
            )
            .andExpect(
                jsonPath("$.data[0].latestMessage")
                    .value(nullValue())
            )
            .andExpect(
                jsonPath("$.data[0].hasUnread")
                    .value(false)
            )
            .andExpect(
                jsonPath("$.nextCursor")
                    .value(createdAt.toString())
            )
            .andExpect(
                jsonPath("$.nextIdAfter")
                    .value(
                        CONVERSATION_ID.toString()
                    )
            )
            .andExpect(
                jsonPath("$.hasNext")
                    .value(true)
            )
            .andExpect(
                jsonPath("$.totalCount")
                    .value(2)
            )
            .andExpect(
                jsonPath("$.sortBy")
                    .value("createdAt")
            )
            .andExpect(
                jsonPath("$.sortDirection")
                    .value("DESCENDING")
            );

        verify(conversationService)
            .getConversations(
                eq(REQUESTER_ID),
                eq("상대"),
                isNull(),
                isNull(),
                eq(1),
                eq("DESCENDING"),
                eq("createdAt")
            );
    }

    @Test
    @DisplayName("limit이 100을 초과하면 400을 반환")
    void getConversations_limitTooLarge_returnsBadRequest()
        throws Exception {

        mockMvc.perform(
                get("/api/conversations")
                    .param("limit", "101")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param(
                        "sortBy",
                        "createdAt"
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("잘못된 정렬 방향은 400을 반환")
    void getConversations_invalidSortDirection_returnsBadRequest()
        throws Exception {

        mockMvc.perform(
                get("/api/conversations")
                    .param("limit", "20")
                    .param(
                        "sortDirection",
                        "INVALID"
                    )
                    .param(
                        "sortBy",
                        "createdAt"
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("sortBy가 누락되면 400을 반환")
    void getConversations_missingSortBy_returnsBadRequest()
        throws Exception {

        mockMvc.perform(
                get("/api/conversations")
                    .param("limit", "20")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );

        verifyNoInteractions(
            conversationService
        );
    }

    @Test
    @DisplayName("cursor와 idAfter 중 하나만 전달하면 400을 반환")
    void getConversations_invalidCursorPair_returnsBadRequest()
        throws Exception {

        when(
            conversationService.getConversations(
                eq(REQUESTER_ID),
                isNull(),
                eq(
                    "2026-08-01T00:00:00Z"
                ),
                isNull(),
                eq(20),
                eq("DESCENDING"),
                eq("createdAt")
            )
        ).thenThrow(
            new BusinessException(
                ErrorCode.INVALID_INPUT
            )
        );

        mockMvc.perform(
                get("/api/conversations")
                    .param(
                        "cursor",
                        "2026-08-01T00:00:00Z"
                    )
                    .param("limit", "20")
                    .param(
                        "sortDirection",
                        "DESCENDING"
                    )
                    .param(
                        "sortBy",
                        "createdAt"
                    )
                    .principal(
                        () -> REQUESTER_ID.toString()
                    )
            )
            .andExpect(status().isBadRequest())
            .andExpect(
                jsonPath("$.errorCode")
                    .value("COMMON_400_1")
            );
    }
}
