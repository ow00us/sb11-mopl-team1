package com.mopl.directmessage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.mopl.directmessage.dto.ConversationCreateRequest;
import com.mopl.directmessage.dto.ConversationCreateResult;
import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.ParticipantSlot;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.ConversationRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
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
            Conversation.create();

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
            Conversation.create();

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
}
