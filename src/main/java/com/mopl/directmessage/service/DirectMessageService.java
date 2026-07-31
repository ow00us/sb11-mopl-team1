package com.mopl.directmessage.service;

import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.user.repository.UserRepository;
import com.mopl.user.entity.User;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DirectMessageService {

    private static final String SORT_BY_CREATED_AT = "createdAt";
    private static final String ASCENDING = "ASCENDING";
    private static final String DESCENDING = "DESCENDING";
    private static final int MAX_LIMIT = 100;

    private final DirectMessageRepository directMessageRepository;
    private final ConversationParticipantRepository participantRepository;
    private final UserRepository userRepository;

    public CursorResponse<DirectMessageDto> getDirectMessages(
        UUID requesterId,
        UUID conversationId,
        String cursor,
        UUID idAfter,
        int limit,
        String sortDirection,
        String sortBy
    ) {
        validateRequest(
            cursor,
            idAfter,
            limit,
            sortDirection,
            sortBy
        );

        List<ConversationParticipant> participants =
            getParticipants(conversationId, requesterId);

        Map<UUID, UUID> receiverIdBySenderId =
            createReceiverIdMap(participants);

        Map<UUID, UserSummary> userSummaries =
            getUserSummaries(participants);

        Instant cursorInstant = parseCursor(cursor);

        PageRequest pageRequest =
            PageRequest.of(0, limit + 1);

        List<DirectMessage> messages;

        if (cursorInstant == null) {
            if (ASCENDING.equals(sortDirection)) {
                messages =
                    directMessageRepository
                        .findAllByConversationIdOrderByCreatedAtAscIdAsc(
                            conversationId,
                            pageRequest
                        );
            } else {
                messages =
                    directMessageRepository
                        .findAllByConversationIdOrderByCreatedAtDescIdDesc(
                            conversationId,
                            pageRequest
                        );
            }
        } else if (ASCENDING.equals(sortDirection)) {
            messages =
                directMessageRepository.findAllByCursorAsc(
                    conversationId,
                    cursorInstant,
                    idAfter,
                    pageRequest
                );
        } else {
            messages =
                directMessageRepository.findAllByCursorDesc(
                    conversationId,
                    cursorInstant,
                    idAfter,
                    pageRequest
                );
        }

        boolean hasNext = messages.size() > limit;

        List<DirectMessage> page =
            hasNext
                ? messages.subList(0, limit)
                : messages;

        List<DirectMessageDto> data = page.stream()
            .map(message ->
                toDto(
                    message,
                    receiverIdBySenderId,
                    userSummaries
                )
            )
            .toList();

        String nextCursor = null;
        UUID nextIdAfter = null;

        if (hasNext && !page.isEmpty()) {
            DirectMessage lastMessage =
                page.get(page.size() - 1);

            nextCursor =
                lastMessage.getCreatedAt().toString();
            nextIdAfter =
                lastMessage.getId();
        }

        long totalCount =
            directMessageRepository.countByConversationId(
                conversationId
            );

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

    private Map<UUID, UUID> createReceiverIdMap(
        List<ConversationParticipant> participants
    ) {
        UUID firstUserId =
            participants
                .get(0)
                .getUserId();

        UUID secondUserId =
            participants
                .get(1)
                .getUserId();

        return Map.of(
            firstUserId,
            secondUserId,
            secondUserId,
            firstUserId
        );
    }

    private DirectMessageDto toDto(
        DirectMessage message,
        Map<UUID, UUID> receiverIdBySenderId,
        Map<UUID, UserSummary> userSummaries
    ) {
        UserSummary sender =
            userSummaries
                .get(message.getSenderId());

        UUID receiverId =
            receiverIdBySenderId
                .get(message.getSenderId());

        if (receiverId == null) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "메시지 발신자가 대화 참여자가 아닙니다."
            );
        }

        UserSummary receiver =
            userSummaries
                .get(receiverId);

        if (sender == null || receiver == null) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        return DirectMessageDto.from(
            message,
            sender,
            receiver
        );
    }

    private List<ConversationParticipant> getParticipants(
        UUID conversationId,
        UUID requesterId
    ) {
        List<ConversationParticipant> participants =
            participantRepository.findAllByConversationId(
                conversationId
            );

        // 참여자 없음: 존재 여부 숨기기 위해 404
        if (participants.isEmpty()) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        // 참여자가 정확히 2명이 아님: 데이터 정합성 오류이므로 500으로 처리
        if (participants.size() != 2) {
            throw new BusinessException(
                ErrorCode.DIRECT_MESSAGE_INVALID_STATE,
                "1:1 대화의 참여자는 정확히 2명이어야 합니다."
            );
        }

        boolean isParticipant = participants.stream()
            .anyMatch(participant ->
                participant.getUserId().equals(requesterId)
            );

        // 요청자가 참여자가 아님: 존재 여부를 숨기기 위해 404
        if (!isParticipant) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        return participants;
    }

    private void validateRequest(
        String cursor,
        UUID idAfter,
        int limit,
        String sortDirection,
        String sortBy
    ) {
        if (limit <= 0 || limit > MAX_LIMIT) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        if (!SORT_BY_CREATED_AT.equals(sortBy)) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        if (!ASCENDING.equals(sortDirection)
            && !DESCENDING.equals(sortDirection)) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }

        boolean hasCursor =
            cursor != null && !cursor.isBlank();

        boolean hasIdAfter =
            idAfter != null;

        if (hasCursor != hasIdAfter) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }
    }

    private Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(cursor);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT
            );
        }
    }

    private Map<UUID, UserSummary> getUserSummaries(
        List<ConversationParticipant> participants
    ) {
        List<UUID> userIds = participants.stream()
            .map(ConversationParticipant::getUserId)
            .toList();

        Map<UUID, UserSummary> summaries =
            userRepository.findAllById(userIds)
                .stream()
                .collect(Collectors.toMap(
                    User::getId,
                    this::toUserSummary
                ));

        if (summaries.size() != userIds.size()) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        return summaries;
    }

    private UserSummary toUserSummary(User user) {
        return new UserSummary(
            user.getId(),
            user.getName(),
            user.getProfileImageUrl()
        );
    }
}
