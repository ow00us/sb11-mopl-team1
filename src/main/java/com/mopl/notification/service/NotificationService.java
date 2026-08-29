package com.mopl.notification.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.notification.dto.NotificationDto;
import com.mopl.notification.dto.NotificationCursorResponse;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import com.mopl.notification.event.NotificationCreatedEvent;
import com.mopl.notification.kafka.NotificationCreateCommand;
import com.mopl.notification.repository.NotificationRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private static final String SORT_BY_CREATED_AT = "createdAt";
    private static final String ASCENDING = "ASCENDING";
    private static final String DESCENDING = "DESCENDING";
    private static final int MAX_LIMIT = 100;

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public NotificationDto create(
        UUID receiverId,
        UUID sourceEventId,
        String title,
        String content,
        NotificationLevel level
    ) {
        return create(
            receiverId,
            sourceEventId,
            null,
            null,
            null,
            title,
            content,
            level
        );
    }

    @Transactional
    public NotificationDto create(
        UUID receiverId,
        UUID sourceEventId,
        NotificationType type,
        UUID resourceId,
        UUID sourceEntityId,
        String title,
        String content,
        NotificationLevel level
    ) {
        Notification notification = Notification.create(
            receiverId,
            sourceEventId,
            type,
            resourceId,
            sourceEntityId,
            title,
            content,
            level
        );

        Notification saved =
            notificationRepository.save(notification);

        NotificationDto notificationDto =
            NotificationDto.from(saved);

        eventPublisher.publishEvent(
            new NotificationCreatedEvent(
                notificationDto
            )
        );

        return notificationDto;
    }

    @Transactional
    public boolean createIfAbsent(NotificationCreateCommand command) {
        UUID notificationId = UUID.randomUUID();
        Instant createdAt = Instant.now();

        int insertedCount = notificationRepository.insertIfAbsent(
                notificationId,
                createdAt,
                command.receiverId(),
                command.sourceEventId(),
                command.type().name(),
                command.resourceId(),
                command.sourceEntityId(),
                command.title(),
                command.content(),
                command.level().name()
            );

        if (insertedCount == 0) {
            return false;
        }

        Notification saved = notificationRepository.findById(notificationId)
            .orElseThrow(() ->
                new IllegalStateException(
                    "저장된 알림을 조회할 수 없습니다."
                )
            );

        NotificationDto notificationDto = NotificationDto.from(saved);

        eventPublisher.publishEvent(
            new NotificationCreatedEvent(notificationDto)
        );

        return true;
    }

    public NotificationCursorResponse getNotifications(
        UUID receiverId,
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

        Instant cursorInstant = parseCursor(cursor);
        PageRequest pageRequest =
            PageRequest.of(
                0,
                limit + 1
            );

        List<Notification> notifications;

        if (cursorInstant == null) {
            Sort.Direction direction =
                ASCENDING.equals(sortDirection)
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;

            Sort sort =
                Sort.by(
                        direction,
                        "createdAt"
                    )
                    .and(
                        Sort.by(
                            direction,
                            "id"
                        )
                    );

            pageRequest =
                PageRequest.of(
                    0,
                    limit + 1,
                    sort
                );

            notifications =
                notificationRepository.findByReceiverId(
                    receiverId,
                    pageRequest
                );
        } else if (ASCENDING.equals(sortDirection)) {
            notifications =
                notificationRepository.findAllAfterAscending(
                    receiverId,
                    cursorInstant,
                    idAfter,
                    pageRequest
                );
        } else {
            notifications =
                notificationRepository.findAllAfterDescending(
                    receiverId,
                    cursorInstant,
                    idAfter,
                    pageRequest
                );
        }

        boolean hasNext =
            notifications.size() > limit;

        List<Notification> page =
            hasNext
                ? notifications.subList(
                0,
                limit
            )
                : notifications;

        List<NotificationDto> data =
            page.stream()
                .map(NotificationDto::from)
                .toList();

        String nextCursor = null;
        UUID nextIdAfter = null;

        if (hasNext && !page.isEmpty()) {
            Notification lastNotification =
                page.get(
                    page.size() - 1
                );

            nextCursor =
                lastNotification
                    .getCreatedAt()
                    .toString();

            nextIdAfter =
                lastNotification.getId();
        }

        long totalCount =
            notificationRepository.countByReceiverId(
                receiverId
            );

        long unreadCount =
            notificationRepository
                .countByReceiverIdAndReadAtIsNull(
                    receiverId
                );

        return NotificationCursorResponse.of(
            data,
            nextCursor,
            nextIdAfter,
            hasNext,
            totalCount,
            unreadCount,
            sortBy,
            sortDirection
        );
    }

    @Transactional
    public void read(
        UUID notificationId,
        UUID receiverId
    ) {
        int updatedCount =
            notificationRepository.markAsReadIfUnread(
                notificationId,
                receiverId,
                Instant.now()
            );

        if (updatedCount == 0) {
            boolean isOwner =
                notificationRepository.findByIdAndReceiverId(
                    notificationId,
                    receiverId
                )
                    .isPresent();

            if (!isOwner) {
                throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND
                );
            }
        }
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
        boolean hasIdAfter = idAfter != null;

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
}
