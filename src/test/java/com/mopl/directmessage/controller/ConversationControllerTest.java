package com.mopl.directmessage.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mopl.directmessage.dto.ConversationCreateRequest;
import com.mopl.directmessage.dto.ConversationCreateResult;
import com.mopl.directmessage.dto.ConversationDto;
import com.mopl.directmessage.service.ConversationService;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.GlobalExceptionHandler;
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
                    .doesNotExist()
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
}
