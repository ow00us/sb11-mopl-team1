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
import com.mopl.directmessage.repository.ConversationListItemProjection;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversationService {

    private static final String SORT_BY_CREATED_AT = "createdAt";
    private static final String ASCENDING = "ASCENDING";
    private static final String DESCENDING = "DESCENDING";
    private static final int MAX_LIMIT = 100;

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

    private void validateConversationLookup(
        UUID requesterId,
        UUID withUserId
    ) {
        if (requesterId.equals(withUserId)) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "자기 자신과의 대화를 조회할 수 없습니다."
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

        if (!users.containsKey(requesterId)) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "인증 사용자 정보를 찾을 수 없습니다."
            );
        }

        if (!users.containsKey(withUserId)) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "대화 상대 사용자를 찾을 수 없습니다."
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

    public CursorResponse<ConversationDto> getConversations(
        UUID requesterId,
        String keywordLike,
        String cursor,
        UUID idAfter,
        int limit,
        String sortDirection,
        String sortBy
    ) {
        validateListRequest(
            cursor,
            idAfter,
            limit,
            sortDirection,
            sortBy
        );

        String normalizedKeyword =
            normalizeKeyword(keywordLike);

        Instant cursorInstant =
            parseConversationCursor(cursor);

        PageRequest pageRequest =
            PageRequest.of(0, limit + 1);

        List<ConversationListItemProjection> items;

        if (ASCENDING.equals(sortDirection)) {
            items =
                participantRepository.findConversationListAsc(
                    requesterId,
                    normalizedKeyword,
                    cursorInstant,
                    idAfter,
                    pageRequest
                );
        } else {
            items =
                participantRepository.findConversationListDesc(
                    requesterId,
                    normalizedKeyword,
                    cursorInstant,
                    idAfter,
                    pageRequest
                );
        }

        boolean hasNext =
            items.size() > limit;

        List<ConversationListItemProjection> page =
            hasNext
                ? items.subList(0, limit)
                : items;

        long totalCount =
            participantRepository.countConversationList(
                requesterId,
                normalizedKeyword
            );

        if (page.isEmpty()) {
            return CursorResponse.of(
                List.of(),
                null,
                null,
                false,
                totalCount,
                sortBy,
                sortDirection
            );
        }

        List<UUID> conversationIds =
            page.stream()
                .map(
                    ConversationListItemProjection
                        ::getConversationId
                )
                .toList();

        Map<UUID, DirectMessage> latestMessageByConversationId =
            getLatestMessageMap(conversationIds);

        Set<UUID> unreadConversationIds =
            new HashSet<>(
                directMessageRepository
                    .findUnreadConversationIds(
                        conversationIds,
                        requesterId
                    )
            );

        Map<UUID, User> users =
            getConversationListUsers(
                requesterId,
                page
            );

        List<ConversationDto> data =
            page.stream()
                .map(item ->
                    toConversationListDto(
                        item,
                        requesterId,
                        users,
                        latestMessageByConversationId,
                        unreadConversationIds
                    )
                )
                .toList();

        String nextCursor = null;
        UUID nextIdAfter = null;

        if (hasNext) {
            ConversationListItemProjection lastItem =
                page.get(page.size() - 1);

            nextCursor =
                lastItem.getCreatedAt().toString();

            nextIdAfter =
                lastItem.getConversationId();
        }

        return CursorResponse.of(
            data,
            nextCursor,
            nextIdAfter,
            hasNext,
            totalCount,
            sortBy,
            sortDirection
        );
    }

    public ConversationDto getConversation(
        UUID requesterId,
        UUID conversationId
    ) {
        List<ConversationParticipant> participants =
            participantRepository.findAllByConversationId(
                conversationId
            );

        boolean isParticipant =
            participants.stream()
                .anyMatch(participant ->
                    participant.getUserId()
                        .equals(requesterId)
                );

        if (participants.isEmpty()) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        if (participants.size() != 2) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "1:1 대화의 참여자는 정확히 2명이어야 합니다."
            );
        }

        if (!isParticipant) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        UUID withUserId =
            participants.stream()
                .map(
                    ConversationParticipant::getUserId
                )
                .filter(userId ->
                    !userId.equals(requesterId))
                .findFirst()
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.DIRECT_MESSAGE_INVALID_STATE
                    )
                );
        Conversation conversation =
            conversationRepository
                .findById(conversationId)
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
                );

        Map<UUID, User> users =
            getUsers(
                requesterId,
                withUserId
            );

        return toDto(
            conversation,
            requesterId,
            withUserId,
            users
        );
    }

    public ConversationDto getConversationWithUser(
        UUID requesterId,
        UUID withUserId
    ) {
        validateConversationLookup(
            requesterId,
            withUserId
        );

        List<UUID> conversationIds =
            participantRepository.findConversationIdsByUserPair(
                requesterId,
                withUserId
            );

        if (conversationIds.isEmpty()) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        if (conversationIds.size() > 1) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "동일한 사용자 사이에 대화가 여러 개 존재합니다."
            );
        }

        Conversation conversation =
            conversationRepository
                .findById(conversationIds.get(0))
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
                );

        Map<UUID, User> users =
            getUsers(
                requesterId,
                withUserId
            );

        return toDto(
            conversation,
            requesterId,
            withUserId,
            users
        );
    }

    private void validateListRequest(
        String cursor,
        UUID idAfter,
        int limit,
        String sortDirection,
        String sortBy
    ) {
        boolean onlyCursorExists =
            cursor != null && idAfter == null;

        boolean onlyIdAfterExists =
            cursor == null && idAfter != null;

        if (onlyCursorExists || onlyIdAfterExists) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "cursor와 idAfter는 함께 전달해야 합니다."
            );
        }

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "limit은 1 이상 100 이하여야 합니다."
            );
        }

        if (!SORT_BY_CREATED_AT.equals(sortBy)) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "sortBy는 createdAt만 허용합니다."
            );
        }

        if (
            !ASCENDING.equals(sortDirection)
                && !DESCENDING.equals(sortDirection)
        ) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "지원하지 않는 정렬 방향입니다."
            );
        }
    }

    private String normalizeKeyword(
        String keywordLike
    ) {
        if (
            keywordLike == null
                || keywordLike.isBlank()
        ) {
            return null;
        }

        return keywordLike.trim();
    }

    private Instant parseConversationCursor(
        String cursor
    ) {
        if (cursor == null) {
            return null;
        }

        try {
            return Instant.parse(cursor);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "커서 형식이 올바르지 않습니다."
            );
        }
    }

    private Map<UUID, DirectMessage> getLatestMessageMap(
        List<UUID> conversationIds
    ) {
        return directMessageRepository
            .findLatestMessagesByConversationIds(
                conversationIds
            )
            .stream()
            .collect(
                Collectors.toMap(
                    DirectMessage::getConversationId,
                    message -> message
                )
            );
    }

    private Map<UUID, User> getConversationListUsers(
        UUID requesterId,
        List<ConversationListItemProjection> page
    ) {
        Set<UUID> userIds =
            page.stream()
                .map(
                    ConversationListItemProjection
                        ::getWithUserId
                )
                .collect(Collectors.toSet());

        userIds.add(requesterId);

        Map<UUID, User> users =
            userRepository
                .findAllById(userIds)
                .stream()
                .collect(
                    Collectors.toMap(
                        User::getId,
                        user -> user
                    )
                );

        if (!users.containsKey(requesterId)) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "인증 사용자 정보를 찾을 수 없습니다."
            );
        }

        return users;
    }

    private ConversationDto toConversationListDto(
        ConversationListItemProjection item,
        UUID requesterId,
        Map<UUID, User> users,
        Map<UUID, DirectMessage>
            latestMessageByConversationId,
        Set<UUID> unreadConversationIds
    ) {
        UUID conversationId =
            item.getConversationId();

        UUID withUserId =
            item.getWithUserId();

        User withUser =
            users.get(withUserId);

        if (withUser == null) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "대화 상대 사용자 정보를 찾을 수 없습니다."
            );
        }

        DirectMessage latestMessage =
            latestMessageByConversationId.get(
                conversationId
            );

        DirectMessageDto latestMessageDto =
            latestMessage == null
                ? null
                : toDirectMessageDto(
                latestMessage,
                requesterId,
                withUserId,
                users
            );

        boolean hasUnread =
            unreadConversationIds.contains(
                conversationId
            );

        return new ConversationDto(
            conversationId,
            toUserSummary(withUser),
            latestMessageDto,
            hasUnread
        );
    }
}
