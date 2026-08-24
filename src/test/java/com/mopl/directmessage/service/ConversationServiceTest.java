package com.mopl.directmessage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import com.mopl.directmessage.dto.ConversationCreateRequest;
import com.mopl.directmessage.dto.ConversationCreateResult;
import com.mopl.directmessage.dto.ConversationDto;
import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.ParticipantSlot;
import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.directmessage.repository.ConversationListItemProjection;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.ConversationRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.util.CursorUtils;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

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

    private static final UUID WITH_USER_ID_2 =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    private static final UUID CONVERSATION_ID_2 =
        UUID.fromString(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        );

    private static final UUID MESSAGE_ID =
        UUID.fromString(
            "cccccccc-cccc-cccc-cccc-cccccccccccc"
        );

    @Mock
    ConversationRepository conversationRepository;

    @Mock
    ConversationParticipantRepository participantRepository;

    @Mock
    DirectMessageRepository directMessageRepository;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    ConversationService conversationService;

    @Test
    @DisplayName("기존 대화가 없으면 대화와 두 참여자를 생성")
    void create_newConversation_success() {
        // given
        ConversationCreateRequest request =
            new ConversationCreateRequest(WITH_USER_ID);

        User requester =
            createUser(REQUESTER_ID, "요청 사용자");

        User withUser =
            createUser(WITH_USER_ID, "상대 사용자");

        Conversation conversation =
            Conversation.create(
                REQUESTER_ID,
                WITH_USER_ID
            );

        ReflectionTestUtils.setField(
            conversation,
            "id",
            CONVERSATION_ID
        );

        when(
            userRepository.findAllById(anyList())
        ).thenReturn(
            List.of(requester, withUser)
        );

        when(
            participantRepository.findConversationIdsByUserPair(
                REQUESTER_ID,
                WITH_USER_ID
            )
        ).thenReturn(List.of());

        when(
            conversationRepository.save(
                any(Conversation.class)
            )
        ).thenReturn(conversation);

        // when
        ConversationCreateResult result =
            conversationService.create(
                REQUESTER_ID,
                request
            );

        // then
        assertThat(result.created()).isTrue();
        assertThat(result.conversation().id())
            .isEqualTo(CONVERSATION_ID);
        assertThat(result.conversation().with().userId())
            .isEqualTo(WITH_USER_ID);
        assertThat(result.conversation().latestMessage())
            .isNull();
        assertThat(result.conversation().hasUnread())
            .isFalse();

        verify(participantRepository)
            .saveAll(
                anyList()
            );

        verify(participantRepository)
            .saveAll(
                org.mockito.ArgumentMatchers.argThat(
                    participants ->
                        hasParticipant(
                            participants,
                            REQUESTER_ID,
                            ParticipantSlot.FIRST
                        )
                            && hasParticipant(
                            participants,
                            WITH_USER_ID,
                            ParticipantSlot.SECOND
                        )
                )
            );
    }

    private boolean hasParticipant(
        Iterable<ConversationParticipant> participants,
        UUID userId,
        ParticipantSlot slot
    ) {
        for (ConversationParticipant participant : participants) {
            if (
                participant.getUserId().equals(userId)
                    && participant.getParticipantSlot() == slot
            ) {
                return true;
            }
        }

        return false;
    }

    private User createUser(
        UUID userId,
        String name
    ) {
        User user =
            User.builder()
                .email(
                    userId + "@example.com"
                )
                .passwordHash("password-hash")
                .name(name)
                .profileImageUrl(null)
                .role(UserRole.USER)
                .locked(false)
                .build();

        ReflectionTestUtils.setField(
            user,
            "id",
            userId
        );

        return user;
    }

    @Test
    @DisplayName("기존 대화가 있으면 새로 생성하지 않고 기존 대화를 반환")
    void create_existingConversation_returnsExisting() {
        // given
        ConversationCreateRequest request =
            new ConversationCreateRequest(WITH_USER_ID);

        User requester =
            createUser(REQUESTER_ID, "요청 사용자");

        User withUser =
            createUser(WITH_USER_ID, "상대 사용자");

        Conversation conversation =
            Conversation.create(
                REQUESTER_ID,
                WITH_USER_ID
            );

        ReflectionTestUtils.setField(
            conversation,
            "id",
            CONVERSATION_ID
        );

        when(
            userRepository.findAllById(anyList())
        ).thenReturn(
            List.of(requester, withUser)
        );

        when(
            participantRepository.findConversationIdsByUserPair(
                REQUESTER_ID,
                WITH_USER_ID
            )
        ).thenReturn(
            List.of(CONVERSATION_ID)
        );

        when(
            conversationRepository.findById(
                CONVERSATION_ID
            )
        ).thenReturn(
            Optional.of(conversation)
        );

        when(
            directMessageRepository
                .findFirstByConversationIdOrderByCreatedAtDescIdDesc(
                    CONVERSATION_ID
                )
        ).thenReturn(Optional.empty());

        when(
            directMessageRepository
                .existsByConversationIdAndSenderIdNotAndReadAtIsNull(
                    CONVERSATION_ID,
                    REQUESTER_ID
                )
        ).thenReturn(false);

        // when
        ConversationCreateResult result =
            conversationService.create(
                REQUESTER_ID,
                request
            );

        // then
        assertThat(result.created()).isFalse();
        assertThat(result.conversation().id())
            .isEqualTo(CONVERSATION_ID);

        verify(
            conversationRepository,
            never()
        ).save(any(Conversation.class));

        verify(
            participantRepository,
            never()
        ).saveAll(anyList());
    }

    @Test
    @DisplayName("자기 자신과 대화를 생성하면 실패")
    void create_withSelf_fails() {
        ConversationCreateRequest request =
            new ConversationCreateRequest(REQUESTER_ID);

        assertThatThrownBy(() ->
            conversationService.create(
                REQUESTER_ID,
                request
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            userRepository,
            conversationRepository,
            participantRepository,
            directMessageRepository
        );
    }

    @Test
    @DisplayName("상대 사용자가 존재하지 않으면 실패")
    void create_userNotFound_fails() {
        ConversationCreateRequest request =
            new ConversationCreateRequest(WITH_USER_ID);

        User requester =
            createUser(REQUESTER_ID, "요청 사용자");

        when(
            userRepository.findAllById(anyList())
        ).thenReturn(
            List.of(requester)
        );

        assertThatThrownBy(() ->
            conversationService.create(
                REQUESTER_ID,
                request
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.RESOURCE_NOT_FOUND
                        )
            );
    }

    @Test
    @DisplayName("같은 사용자 쌍의 대화가 여러 개면 데이터 상태 오류")
    void create_duplicateConversations_fails() {
        ConversationCreateRequest request =
            new ConversationCreateRequest(WITH_USER_ID);

        User requester =
            createUser(REQUESTER_ID, "요청 사용자");

        User withUser =
            createUser(WITH_USER_ID, "상대 사용자");

        when(
            userRepository.findAllById(anyList())
        ).thenReturn(
            List.of(requester, withUser)
        );

        when(
            participantRepository.findConversationIdsByUserPair(
                REQUESTER_ID,
                WITH_USER_ID
            )
        ).thenReturn(
            List.of(
                UUID.randomUUID(),
                UUID.randomUUID()
            )
        );

        assertThatThrownBy(() ->
            conversationService.create(
                REQUESTER_ID,
                request
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.DIRECT_MESSAGE_INVALID_STATE
                        )
            );
    }

    @Test
    @DisplayName("대화 참여자는 대화 ID로 대화를 조회")
    void getConversation_participant_returnsConversation() {
        // given
        Conversation conversation =
            Conversation.create(
                REQUESTER_ID,
                WITH_USER_ID
            );

        ReflectionTestUtils.setField(
            conversation,
            "id",
            CONVERSATION_ID
        );

        ConversationParticipant requester =
            ConversationParticipant.create(
                CONVERSATION_ID,
                REQUESTER_ID,
                ParticipantSlot.FIRST
            );

        ConversationParticipant withUser =
            ConversationParticipant.create(
                CONVERSATION_ID,
                WITH_USER_ID,
                ParticipantSlot.SECOND
            );

        User requesterUser =
            createUser(
                REQUESTER_ID,
                "요청 사용자"
            );

        User otherUser =
            createUser(
                WITH_USER_ID,
                "상대 사용자"
            );

        when(
            conversationRepository.findById(
                CONVERSATION_ID
            )
        ).thenReturn(
            Optional.of(conversation)
        );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(
            List.of(
                requester,
                withUser
            )
        );

        when(
            userRepository.findAllById(
                List.of(
                    REQUESTER_ID,
                    WITH_USER_ID
                )
            )
        ).thenReturn(
            List.of(
                requesterUser,
                otherUser
            )
        );

        when(
            directMessageRepository
                .findFirstByConversationIdOrderByCreatedAtDescIdDesc(
                    CONVERSATION_ID
                )
        ).thenReturn(Optional.empty());

        when(
            directMessageRepository
                .existsByConversationIdAndSenderIdNotAndReadAtIsNull(
                    CONVERSATION_ID,
                    REQUESTER_ID
                )
        ).thenReturn(false);

        // when
        ConversationDto result =
            conversationService.getConversation(
                REQUESTER_ID,
                CONVERSATION_ID
            );

        // then
        assertThat(result.id())
            .isEqualTo(CONVERSATION_ID);

        assertThat(result.with().userId())
            .isEqualTo(WITH_USER_ID);

        assertThat(result.latestMessage())
            .isNull();

        assertThat(result.hasUnread())
            .isFalse();
    }

    @Test
    @DisplayName("대화 참여자가 없으면 대화를 찾을 수 없음")
    void getConversation_noParticipants_fails() {
        // given
        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(List.of());

        // when & then
        assertThatThrownBy(() ->
            conversationService.getConversation(
                REQUESTER_ID,
                CONVERSATION_ID
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(
                        exception.getErrorCode()
                    ).isEqualTo(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
            );
    }

    @Test
    @DisplayName("1대1 대화의 참여자가 정확히 두 명이 아니면 실패")
    void getConversation_invalidParticipantCount_fails() {
        // given
        ConversationParticipant requester =
            ConversationParticipant.create(
                CONVERSATION_ID,
                REQUESTER_ID,
                ParticipantSlot.FIRST
            );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(
            List.of(requester)
        );

        // when & then
        assertThatThrownBy(() ->
            conversationService.getConversation(
                REQUESTER_ID,
                CONVERSATION_ID
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception -> {
                    assertThat(
                        exception.getErrorCode()
                    ).isEqualTo(
                        ErrorCode.DIRECT_MESSAGE_INVALID_STATE
                    );

                    assertThat(
                        exception.getMessage()
                    ).isEqualTo(
                        "1:1 대화의 참여자는 정확히 2명이어야 합니다."
                    );
                }
            );
    }

    @Test
    @DisplayName("대화에 참여하지 않은 사용자는 대화를 조회할 수 없음")
    void getConversation_nonParticipant_fails() {
        // given
        UUID otherUserId =
            UUID.randomUUID();

        ConversationParticipant first =
            ConversationParticipant.create(
                CONVERSATION_ID,
                WITH_USER_ID,
                ParticipantSlot.FIRST
            );

        ConversationParticipant second =
            ConversationParticipant.create(
                CONVERSATION_ID,
                otherUserId,
                ParticipantSlot.SECOND
            );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(
            List.of(first, second)
        );

        // when & then
        assertThatThrownBy(() ->
            conversationService.getConversation(
                REQUESTER_ID,
                CONVERSATION_ID
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(
                        exception.getErrorCode()
                    ).isEqualTo(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
            );
    }

    @Test
    @DisplayName("특정 사용자와의 대화가 없으면 사용자 존재 여부와 관계없이 실패")
    void getConversationWithUser_notFound_fails() {
        // given
        when(
            participantRepository
                .findConversationIdsByUserPair(
                    REQUESTER_ID,
                    WITH_USER_ID
                )
        ).thenReturn(List.of());

        // when & then
        assertThatThrownBy(() ->
            conversationService
                .getConversationWithUser(
                    REQUESTER_ID,
                    WITH_USER_ID
                )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(
                        exception.getErrorCode()
                    ).isEqualTo(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
            );

        verifyNoInteractions(
            userRepository,
            conversationRepository,
            directMessageRepository
        );
    }

    @Test
    @DisplayName("자기 자신과의 대화를 조회하면 조회 문맥의 메시지로 실패")
    void getConversationWithUser_self_fails() {
        // when & then
        assertThatThrownBy(() ->
            conversationService
                .getConversationWithUser(
                    REQUESTER_ID,
                    REQUESTER_ID
                )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception -> {
                    assertThat(
                        exception.getErrorCode()
                    ).isEqualTo(
                        ErrorCode.INVALID_INPUT
                    );

                    assertThat(
                        exception.getMessage()
                    ).isEqualTo(
                        "자기 자신과의 대화를 조회할 수 없습니다."
                    );
                }
            );

        verifyNoInteractions(
            participantRepository,
            userRepository,
            conversationRepository,
            directMessageRepository
        );
    }

    @Test
    @DisplayName("대화 목록을 최근 메시지와 미읽음 상태를 포함해 조회")
    void getConversations_success() {
        // given
        Instant firstCreatedAt =
            Instant.parse("2026-08-01T01:00:00Z");

        ConversationListItemProjection firstItem =
            mock(ConversationListItemProjection.class);

        ConversationListItemProjection secondItem =
            mock(ConversationListItemProjection.class);

        when(firstItem.getConversationId())
            .thenReturn(CONVERSATION_ID);

        when(firstItem.getCreatedAt())
            .thenReturn(firstCreatedAt);

        when(firstItem.getWithUserId())
            .thenReturn(WITH_USER_ID);

        when(
            participantRepository.findFirstConversationListDesc(
                eq(REQUESTER_ID),
                isNull(),
                any()
            )
        ).thenReturn(
            List.of(
                firstItem,
                secondItem
            )
        );

        when(
            participantRepository.countConversationList(
                REQUESTER_ID,
                null
            )
        ).thenReturn(2L);

        DirectMessage latestMessage =
            DirectMessage.create(
                CONVERSATION_ID,
                WITH_USER_ID,
                "최근 메시지"
            );

        ReflectionTestUtils.setField(
            latestMessage,
            "id",
            MESSAGE_ID
        );

        ReflectionTestUtils.setField(
            latestMessage,
            "createdAt",
            firstCreatedAt
        );

        when(
            directMessageRepository
                .findLatestMessagesByConversationIds(
                    List.of(CONVERSATION_ID)
                )
        ).thenReturn(
            List.of(latestMessage)
        );

        when(
            directMessageRepository
                .findUnreadConversationIds(
                    List.of(CONVERSATION_ID),
                    REQUESTER_ID
                )
        ).thenReturn(
            List.of(CONVERSATION_ID)
        );

        User requester =
            createUser(
                REQUESTER_ID,
                "요청 사용자"
            );

        User withUser =
            createUser(
                WITH_USER_ID,
                "상대 사용자"
            );

        when(
            userRepository.findAllById(
                anyCollection()
            )
        ).thenReturn(
            List.of(
                requester,
                withUser
            )
        );

        // when
        CursorResponse<ConversationDto> result =
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                null,
                null,
                1,
                "DESCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data())
            .hasSize(1);

        ConversationDto conversation =
            result.data().get(0);

        assertThat(conversation.id())
            .isEqualTo(CONVERSATION_ID);

        assertThat(conversation.with().userId())
            .isEqualTo(WITH_USER_ID);

        assertThat(conversation.latestMessage())
            .isNotNull();

        assertThat(
            conversation.latestMessage().id()
        ).isEqualTo(MESSAGE_ID);

        assertThat(conversation.hasUnread())
            .isTrue();

        assertThat(result.hasNext())
            .isTrue();

        assertThat(result.nextCursor())
            .isEqualTo(
                CursorUtils.encodeInstant(firstCreatedAt)
            );

        assertThat(result.nextIdAfter())
            .isEqualTo(CONVERSATION_ID);

        assertThat(result.totalCount())
            .isEqualTo(2L);
    }

    @Test
    @DisplayName("조회할 대화가 없으면 빈 커서 응답을 반환")
    void getConversations_empty_returnsEmptyResponse() {
        // given
        when(
            participantRepository.findFirstConversationListDesc(
                eq(REQUESTER_ID),
                isNull(),
                any()
            )
        ).thenReturn(List.of());

        when(
            participantRepository.countConversationList(
                REQUESTER_ID,
                null
            )
        ).thenReturn(0L);

        // when
        CursorResponse<ConversationDto> result =
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                null,
                null,
                20,
                "DESCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data())
            .isEmpty();

        assertThat(result.hasNext())
            .isFalse();

        assertThat(result.nextCursor())
            .isNull();

        assertThat(result.nextIdAfter())
            .isNull();

        assertThat(result.totalCount())
            .isZero();

        verifyNoInteractions(
            directMessageRepository,
            userRepository
        );
    }

    @Test
    @DisplayName("ASCENDING 요청은 오름차순 Repository를 사용")
    void getConversations_ascending_usesAscendingQuery() {
        // given
        ConversationListItemProjection item =
            mock(ConversationListItemProjection.class);

        when(item.getConversationId())
            .thenReturn(CONVERSATION_ID);

        when(item.getWithUserId())
            .thenReturn(WITH_USER_ID);

        when(
            participantRepository.findFirstConversationListAsc(
                eq(REQUESTER_ID),
                isNull(),
                any()
            )
        ).thenReturn(
            List.of(item)
        );

        when(
            participantRepository.countConversationList(
                REQUESTER_ID,
                null
            )
        ).thenReturn(1L);

        when(
            directMessageRepository
                .findLatestMessagesByConversationIds(
                    List.of(CONVERSATION_ID)
                )
        ).thenReturn(List.of());

        when(
            directMessageRepository
                .findUnreadConversationIds(
                    List.of(CONVERSATION_ID),
                    REQUESTER_ID
                )
        ).thenReturn(List.of());

        User requester =
            createUser(
                REQUESTER_ID,
                "요청 사용자"
            );

        User withUser =
            createUser(
                WITH_USER_ID,
                "상대 사용자"
            );

        when(
            userRepository.findAllById(
                anyCollection()
            )
        ).thenReturn(
            List.of(
                requester,
                withUser
            )
        );

        // when
        CursorResponse<ConversationDto> result =
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                null,
                null,
                20,
                "ASCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data())
            .hasSize(1);

        assertThat(
            result.data().get(0).latestMessage()
        ).isNull();

        assertThat(
            result.data().get(0).hasUnread()
        ).isFalse();

        assertThat(result.hasNext())
            .isFalse();

        verify(participantRepository)
            .findFirstConversationListAsc(
                eq(REQUESTER_ID),
                isNull(),
                any()
            );
    }

    @Test
    @DisplayName("cursor만 전달하면 대화 목록 조회에 실패")
    void getConversations_cursorOnly_fails() {
        // given
        String cursor = CursorUtils.encodeInstant(Instant.parse("2026-08-01T01:00:00Z")
        );

        // when & then
        assertThatThrownBy(() ->
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                cursor,
                null,
                20,
                "DESCENDING",
                "createdAt"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            participantRepository,
            directMessageRepository,
            userRepository
        );
    }

    @Test
    @DisplayName("idAfter만 전달하면 대화 목록 조회 실패")
    void getConversation_idAfterOnly_fails() {
        // when & then
        assertThatThrownBy(() ->
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                null,
                CONVERSATION_ID,
                20,
                "DESCENDING",
                "createdAt"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            participantRepository,
            directMessageRepository,
            userRepository
        );
    }

    @Test
    @DisplayName("limit이 1보다 작으면 대화 목록 조회에 실패")
    void getConversations_limitBelowMinimum_fails() {
        assertThatThrownBy(() ->
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                null,
                null,
                0,
                "DESCENDING",
                "createdAt"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            participantRepository,
            directMessageRepository,
            userRepository
        );
    }

    @Test
    @DisplayName("limit이 100보다 크면 대화 목록 조회에 실패")
    void getConversations_limitAboveMaximum_fails() {
        assertThatThrownBy(() ->
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                null,
                null,
                101,
                "DESCENDING",
                "createdAt"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            participantRepository,
            directMessageRepository,
            userRepository
        );
    }

    @Test
    @DisplayName("sortBy가 createdAt이 아니면 대화 목록 조회에 실패")
    void getConversation_invalidSortBy_fails() {
        assertThatThrownBy(() ->
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                null,
                null,
                20,
                "DESCENDING",
                "name"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            participantRepository,
            directMessageRepository,
            userRepository
        );
    }

    @Test
    @DisplayName("지원하지 않는 정렬 방향이면 대화 목록 조회에 실패")
    void getConversations_invalidSortDirection_fails() {
        assertThatThrownBy(() ->
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                null,
                null,
                20,
                "INVALID",
                "createdAt"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            participantRepository,
            directMessageRepository,
            userRepository
        );
    }

    @Test
    @DisplayName("cursor와 idAfter를 함께 전달하면 다음 페이지를 조회")
    void getConversations_cursorPair_success() {
        // given
        Instant cursorInstant =
            Instant.parse("2026-08-01T01:00:00Z");

        String cursor =
            CursorUtils.encodeInstant(cursorInstant);

        when(
            participantRepository.findConversationListDesc(
                eq(REQUESTER_ID),
                isNull(),
                eq(cursorInstant),
                eq(CONVERSATION_ID),
                any()
            )
        ).thenReturn(List.of());

        when(
            participantRepository.countConversationList(
                REQUESTER_ID,
                null
            )
        ).thenReturn(0L);

        // when
        CursorResponse<ConversationDto> result =
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                cursor,
                CONVERSATION_ID,
                20,
                "DESCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data()).isEmpty();
        assertThat(result.hasNext()).isFalse();

        verify(participantRepository)
            .findConversationListDesc(
                eq(REQUESTER_ID),
                isNull(),
                eq(cursorInstant),
                eq(CONVERSATION_ID),
                any()
            );
    }

    @Test
    @DisplayName("Base64 형식이 잘못된 커서면 대화 목록 조회에 실패")
    void getConversations_invalidBase64Cursor_fails() {
        assertThatThrownBy(() ->
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                "%%%",
                CONVERSATION_ID,
                20,
                "DESCENDING",
                "createdAt"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            participantRepository,
            directMessageRepository,
            userRepository
        );
    }

    @Test
    @DisplayName("날짜 형식이 잘못된 커서면 대화 목록 조회에 실패")
    void getConversations_invalidCursorDate_fails() {
        // given
        String cursor =
            CursorUtils.encode("not-an-instant");

        // when & then
        assertThatThrownBy(() ->
            conversationService.getConversations(
                REQUESTER_ID,
                null,
                cursor,
                CONVERSATION_ID,
                20,
                "DESCENDING",
                "createdAt"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT)
            );

        verifyNoInteractions(
            participantRepository,
            directMessageRepository,
            userRepository
        );
    }
}
