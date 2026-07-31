package com.mopl.directmessage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.directmessage.entity.ParticipantSlot;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DirectMessageServiceTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    private static final UUID USER_ID_1 =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID USER_ID_2 =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID OTHER_USER_ID =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    @Mock
    DirectMessageRepository directMessageRepository;

    @Mock
    ConversationParticipantRepository participantRepository;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    DirectMessageService directMessageService;

    @Test
    @DisplayName("DM 첫 페이지를 최신순으로 조회")
    void getDirectMessages_firstPageDescending_success() {
        // given
        stubParticipantsAndUsers();

        DirectMessage latest = createMessage(
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3"
            ),
            USER_ID_1,
            Instant.parse("2026-07-30T03:00:00Z"),
            "최신 메시지"
        );

        DirectMessage middle = createMessage(
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2"
            ),
            USER_ID_2,
            Instant.parse("2026-07-30T02:00:00Z"),
            "중간 메시지"
        );

        DirectMessage oldest = createMessage(
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1"
            ),
            USER_ID_1,
            Instant.parse("2026-07-30T01:00:00Z"),
            "오래된 메시지"
        );

        when(
            directMessageRepository
                .findAllByConversationIdOrderByCreatedAtDescIdDesc(
                    eq(CONVERSATION_ID),
                    any(Pageable.class)
                )
        ).thenReturn(
            List.of(latest, middle, oldest)
        );

        when(
            directMessageRepository
                .countByConversationId(CONVERSATION_ID)
        ).thenReturn(3L);

        // when
        CursorResponse<DirectMessageDto> result =
            directMessageService.getDirectMessages(
                USER_ID_1,
                CONVERSATION_ID,
                null,
                null,
                2,
                "DESCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data())
            .extracting(DirectMessageDto::id)
            .containsExactly(
                latest.getId(),
                middle.getId()
            );

        assertThat(result.data().get(0).sender().userId())
            .isEqualTo(USER_ID_1);
        assertThat(result.data().get(0).receiver().userId())
            .isEqualTo(USER_ID_2);

        assertThat(result.data().get(1).sender().userId())
            .isEqualTo(USER_ID_2);
        assertThat(result.data().get(1).receiver().userId())
            .isEqualTo(USER_ID_1);

        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor())
            .isEqualTo(middle.getCreatedAt().toString());
        assertThat(result.nextIdAfter())
            .isEqualTo(middle.getId());
        assertThat(result.totalCount()).isEqualTo(3L);
        assertThat(result.sortBy()).isEqualTo("createdAt");
        assertThat(result.sortDirection())
            .isEqualTo("DESCENDING");
    }

    @Test
    @DisplayName("DM 첫 페이지를 오래된순으로 조회")
    void getDirectMessages_firstPageAscending_success() {
        // given
        stubParticipantsAndUsers();

        when(
            directMessageRepository
                .findAllByConversationIdOrderByCreatedAtAscIdAsc(
                    eq(CONVERSATION_ID),
                    any(Pageable.class)
                )
        ).thenReturn(List.of());

        // when
        CursorResponse<DirectMessageDto> result =
            directMessageService.getDirectMessages(
                USER_ID_1,
                CONVERSATION_ID,
                null,
                null,
                10,
                "ASCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data()).isEmpty();

        verify(directMessageRepository)
            .findAllByConversationIdOrderByCreatedAtAscIdAsc(
                eq(CONVERSATION_ID),
                any(Pageable.class)
            );
    }

    @Test
    @DisplayName("최신순 커서 이후의 DM을 조회")
    void getDirectMessages_afterCursorDescending_success() {
        // given
        stubParticipantsAndUsers();

        Instant cursor =
            Instant.parse("2026-07-30T03:00:00Z");

        UUID idAfter =
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2"
            );

        when(
            directMessageRepository.findAllByCursorDesc(
                eq(CONVERSATION_ID),
                eq(cursor),
                eq(idAfter),
                any(Pageable.class)
            )
        ).thenReturn(List.of());

        // when
        CursorResponse<DirectMessageDto> result =
            directMessageService.getDirectMessages(
                USER_ID_1,
                CONVERSATION_ID,
                cursor.toString(),
                idAfter,
                10,
                "DESCENDING",
                "createdAt"
            );

        // then
        assertThat(result.data()).isEmpty();
        assertThat(result.hasNext()).isFalse();

        verify(directMessageRepository)
            .findAllByCursorDesc(
                eq(CONVERSATION_ID),
                eq(cursor),
                eq(idAfter),
                any(Pageable.class)
            );
    }

    @Test
    @DisplayName("대화 참여자가 아니면 DM을 조회할 수 없음")
    void getDirectMessages_nonParticipant_fails() {
        // given
        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(participants());

        // when & then
        assertThatThrownBy(() ->
            directMessageService.getDirectMessages(
                OTHER_USER_ID,
                CONVERSATION_ID,
                null,
                null,
                10,
                "DESCENDING",
                "createdAt"
            )
        ).isInstanceOf(BusinessException.class);

        verifyNoInteractions(
            userRepository,
            directMessageRepository
        );
    }

    @Test
    @DisplayName("cursor와 idAfter 중 하나만 있으면 조회 실패")
    void getDirectMessages_incompleteCursor_fails() {
        // when & then
        assertThatThrownBy(() ->
            directMessageService.getDirectMessages(
                USER_ID_1,
                CONVERSATION_ID,
                "2026-07-30T03:00:00Z",
                null,
                10,
                "DESCENDING",
                "createdAt"
            )
        ).isInstanceOf(BusinessException.class);

        verifyNoInteractions(
            participantRepository,
            userRepository,
            directMessageRepository
        );
    }

    private void stubParticipantsAndUsers() {
        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(participants());

        User user1 =
            createUser(USER_ID_1, "사용자1");

        User user2 =
            createUser(USER_ID_2, "사용자2");

        when(
            userRepository.findAllById(anyList())
        ).thenReturn(
            List.of(user1, user2)
        );
    }

    private List<ConversationParticipant> participants() {
        return List.of(
            ConversationParticipant.create(
                CONVERSATION_ID,
                USER_ID_1,
                ParticipantSlot.FIRST
            ),
            ConversationParticipant.create(
                CONVERSATION_ID,
                USER_ID_2,
                ParticipantSlot.SECOND
            )
        );
    }

    private User createUser(
        UUID userId,
        String name
    ) {
        User user = mock(User.class);

        when(user.getId()).thenReturn(userId);
        when(user.getName()).thenReturn(name);
        when(user.getProfileImageUrl()).thenReturn(null);

        return user;
    }

    private DirectMessage createMessage(
        UUID messageId,
        UUID senderId,
        Instant createdAt,
        String content
    ) {
        DirectMessage message = DirectMessage.create(
            CONVERSATION_ID,
            senderId,
            content
        );

        ReflectionTestUtils.setField(
            message,
            "id",
            messageId
        );

        ReflectionTestUtils.setField(
            message,
            "createdAt",
            createdAt
        );

        return message;
    }

    @Test
    @DisplayName("대화에 참여하지 않은 사용자가 DM 목록을 조회하면 실패한다")
    void getDirectMessages_nonParticipant_throwsResourceNotFound() {
        // given
        UUID conversationId = UUID.randomUUID();
        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();
        UUID nonParticipantId = UUID.randomUUID();

        ConversationParticipant firstParticipant =
            ConversationParticipant.create(
                conversationId,
                firstUserId,
                ParticipantSlot.FIRST
            );

        ConversationParticipant secondParticipant =
            ConversationParticipant.create(
                conversationId,
                secondUserId,
                ParticipantSlot.SECOND
            );

        given(
            participantRepository.findAllByConversationId(
                conversationId
            )
        ).willReturn(
            List.of(
                firstParticipant,
                secondParticipant
            )
        );

        // when & then
        assertThatThrownBy(() ->
            directMessageService.getDirectMessages(
                nonParticipantId,
                conversationId,
                null,
                null,
                10,
                "DESCENDING",
                "createdAt"
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
    @DisplayName("1대1 대화의 참여자가 한 명뿐이면 DM 데이터 상태 오류가 발생한다")
    void getDirectMessages_oneParticipant_throwsInvalidState() {
        // given
        UUID conversationId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();

        ConversationParticipant participant =
            ConversationParticipant.create(
                conversationId,
                requesterId,
                ParticipantSlot.FIRST
            );

        given(
            participantRepository.findAllByConversationId(
                conversationId
            )
        ).willReturn(
            List.of(participant)
        );

        // when & then
        assertThatThrownBy(() ->
            directMessageService.getDirectMessages(
                requesterId,
                conversationId,
                null,
                null,
                10,
                "DESCENDING",
                "createdAt"
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception -> {
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.DIRECT_MESSAGE_INVALID_STATE
                        );

                    assertThat(exception.getMessage())
                        .isEqualTo(
                            "1:1 대화의 참여자는 정확히 2명이어야 합니다."
                        );
                }
            );
    }

    @Test
    @DisplayName("DM 수신자가 읽음 처리하면 readAt을 기록")
    void read_receiver_success() {
        // given
        DirectMessage message = createMessage(
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            ),
            USER_ID_1,
            Instant.parse("2026-07-31T01:00:00Z"),
            "읽을 메시지"
        );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(participants());

        when(
            directMessageRepository.findByIdAndConversationId(
                message.getId(),
                CONVERSATION_ID
            )
        ).thenReturn(Optional.of(message));

        // when
        directMessageService.read(
            USER_ID_2,
            CONVERSATION_ID,
            message.getId()
        );

        // then
        assertThat(message.getReadAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 읽은 DM을 다시 읽음 처리하면 최초 readAt을 유지")
    void read_alreadyRead_preservesFirstReadAt() {
        // given
        DirectMessage message = createMessage(
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            ),
            USER_ID_1,
            Instant.parse("2026-07-31T01:00:00Z"),
            "읽을 메시지"
        );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(participants());

        when(
            directMessageRepository.findByIdAndConversationId(
                message.getId(),
                CONVERSATION_ID
            )
        ).thenReturn(Optional.of(message));

        directMessageService.read(
            USER_ID_2,
            CONVERSATION_ID,
            message.getId()
        );

        Instant firstReadAt = message.getReadAt();

        // when
        directMessageService.read(
            USER_ID_2,
            CONVERSATION_ID,
            message.getId()
        );

        // then
        assertThat(message.getReadAt())
            .isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("DM 발신자가 자신의 메시지를 읽음 처리하면 권한 오류가 발생한다")
    void read_sender_throwsForbidden() {
        // given
        DirectMessage message = createMessage(
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            ),
            USER_ID_1,
            Instant.parse("2026-07-31T01:00:00Z"),
            "읽을 메시지"
        );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(participants());

        when(
            directMessageRepository.findByIdAndConversationId(
                message.getId(),
                CONVERSATION_ID
            )
        ).thenReturn(Optional.of(message));

        // when & then
        assertThatThrownBy(() ->
            directMessageService.read(
                USER_ID_1,
                CONVERSATION_ID,
                message.getId()
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.FORBIDDEN
                        )
            );

        assertThat(message.getReadAt()).isNull();
    }

    @Test
    @DisplayName("대화에 속하지 않은 DM을 읽음 처리하면 리소스 없음 오류가 발생한다")
    void read_messageNotInConversation_throwsResourceNotFound() {
        // given
        UUID messageId =
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(participants());

        when(
            directMessageRepository.findByIdAndConversationId(
                messageId,
                CONVERSATION_ID
            )
        ).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
            directMessageService.read(
                USER_ID_2,
                CONVERSATION_ID,
                messageId
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
    @DisplayName("메시지 발신자가 대화 참여자가 아니면 DM 데이터 상태 오류가 발생한다")
    void read_senderNotParticipant_throwsInvalidState() {
        // given
        UUID messageId =
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

        UUID invalidSenderId =
            UUID.fromString(
                "33333333-3333-3333-3333-333333333333"
            );

        DirectMessage message = createMessage(
            messageId,
            invalidSenderId,
            Instant.parse("2026-07-31T01:00:00Z"),
            "잘못된 발신자가 저장된 메시지"
        );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(participants());

        when(
            directMessageRepository.findByIdAndConversationId(
                messageId,
                CONVERSATION_ID
            )
        ).thenReturn(Optional.of(message));

        // when & then
        assertThatThrownBy(() ->
            directMessageService.read(
                USER_ID_2,
                CONVERSATION_ID,
                messageId
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

        assertThat(message.getReadAt()).isNull();
    }

    @Test
    @DisplayName("대화 비참여자가 DM을 읽음 처리하면 리소스 없음 오류가 발생한다")
    void read_nonParticipant_throwsResourceNotFound() {
        // given
        UUID nonParticipantId =
            UUID.fromString(
                "33333333-3333-3333-3333-333333333333"
            );

        UUID messageId =
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            );

        when(
            participantRepository.findAllByConversationId(
                CONVERSATION_ID
            )
        ).thenReturn(participants());

        // when & then
        assertThatThrownBy(() ->
            directMessageService.read(
                nonParticipantId,
                CONVERSATION_ID,
                messageId
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

        verifyNoInteractions(
            directMessageRepository
        );
    }
}
