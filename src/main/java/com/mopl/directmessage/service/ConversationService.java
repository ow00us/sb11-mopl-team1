package com.mopl.directmessage.service;

import com.mopl.directmessage.dto.ConversationCreateRequest;
import com.mopl.directmessage.dto.ConversationCreateResult;
import com.mopl.directmessage.dto.ConversationDto;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.directmessage.entity.ParticipantSlot;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.ConversationRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;
    private final DirectMessageRepository directMessageRepository;
    private final UserRepository userRepository;

    @Transactional
    public ConversationCreateResult create(
        UUID requesterId,
        ConversationCreateRequest request
    ) {
        UUID withUserId = request.withUserId();

        validateUsers(
            requesterId,
            withUserId
        );

        Map<UUID, User> users =
            getUsers(
                requesterId,
                withUserId
            );

        List<UUID> conversationIds =
            participantRepository
                .findConversationIdsByUserPair(
                    requesterId,
                    withUserId
                );

        if (conversationIds.size() > 1) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "동일한 사용자 사이에 대화가 여러 개 존재합니다."
            );
        }

        if (conversationIds.size() == 1) {
            Conversation conversation =
                conversationRepository
                    .findById(conversationIds.get(0))
                    .orElseThrow(() ->
                        new BusinessException(
                            ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                            "참여자 정보에 해당하는 대화가 없습니다."
                        )
                    );

            ConversationDto response =
                toDto(
                    conversation,
                    requesterId,
                    withUserId,
                    users
                );

            return new ConversationCreateResult(
                response,
                false
            );
        }

        Conversation conversation =
            conversationRepository.save(
                Conversation.create()
            );

        ConversationParticipant requester =
            ConversationParticipant.create(
                conversation.getId(),
                requesterId,
                ParticipantSlot.FIRST
            );

        ConversationParticipant withUser =
            ConversationParticipant.create(
                conversation.getId(),
                withUserId,
                ParticipantSlot.SECOND
            );

        participantRepository.saveAll(
            List.of(
                requester,
                withUser
            )
        );

        ConversationDto response =
            new ConversationDto(
                conversation.getId(),
                toUserSummary(
                    users.get(withUserId)
                ),
                null,
                false
            );

        return new ConversationCreateResult(
            response,
            true
        );
    }

    private void validateUsers(
        UUID requesterId,
        UUID withUserId
    ) {
        if (requesterId.equals(withUserId)) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "자기 자신과 대화를 생성할 수 없습니다."
            );
        }
    }

    private Map<UUID, User> getUsers(
        UUID requesterId,
        UUID withUserId
    ) {
        List<UUID> userIds =
            List.of(
                requesterId,
                withUserId
            );

        Map<UUID, User> users =
            userRepository.findAllById(userIds)
                .stream()
                .collect(
                    Collectors.toMap(
                        User::getId,
                        user -> user
                    )
                );

        if (users.size() != 2) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        return users;
    }

    private ConversationDto toDto(
        Conversation conversation,
        UUID requesterId,
        UUID withUserId,
        Map<UUID, User> users
    ) {
        DirectMessageDto latestMessage =
            directMessageRepository
                .findFirstByConversationIdOrderByCreatedAtDescIdDesc(
                    conversation.getId()
                )
                .map(message ->
                    toDirectMessageDto(
                        message,
                        requesterId,
                        withUserId,
                        users
                    )
                )
                .orElse(null);

        boolean hasUnread =
            directMessageRepository
                .existsByConversationIdAndSenderIdNotAndReadAtIsNull(
                    conversation.getId(),
                    requesterId
                );

        return new ConversationDto(
            conversation.getId(),
            toUserSummary(
                users.get(withUserId)
            ),
            latestMessage,
            hasUnread
        );
    }

    private DirectMessageDto toDirectMessageDto(
        DirectMessage message,
        UUID requesterId,
        UUID withUserId,
        Map<UUID, User> users
    ) {
        UUID senderId = message.getSenderId();
        UUID receiverId;

        if (senderId.equals(requesterId)) {
            receiverId = withUserId;
        } else if (senderId.equals(withUserId)) {
            receiverId = requesterId;
        } else {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "메시지 발신자가 대화 참여자가 아닙니다."
            );
        }

        User sender = users.get(senderId);
        User receiver = users.get(receiverId);

        if (sender == null || receiver == null) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "메시지 참여자 정보를 찾을 수 없습니다."
            );
        }

        return DirectMessageDto.from(
            message,
            toUserSummary(sender),
            toUserSummary(receiver)
        );
    }

    private UserSummary toUserSummary(
        User user
    ) {
        if (user == null) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        return new UserSummary(
            user.getId(),
            user.getName(),
            user.getProfileImageUrl()
        );
    }
}
