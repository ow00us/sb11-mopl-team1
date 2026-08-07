package com.mopl.notification.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.notification.dto.NotificationDto;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.event.NotificationCreatedEvent;
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
        Notification notification = Notification.create(
            receiverId,
            sourceEventId,
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

    public CursorResponse<NotificationDto> getUnreadNotifications(
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
        PageRequest pageRequest = PageRequest.of(0, limit + 1);

        List<Notification> notifications;

        if (cursorInstant == null) {
            Sort.Direction direction =
                ASCENDING.equals(sortDirection)
                    ? Sort.Direction.ASC
                    : Sort.Direction.DESC;

            Sort sort = Sort.by(direction, "createdAt")
                .and(Sort.by(direction, "id"));

            pageRequest = PageRequest.of(0, limit + 1, sort);

            notifications =
                notificationRepository
                    .findByReceiverIdAndReadAtIsNull(
                        receiverId,
                        pageRequest
                    );
        } else if (ASCENDING.equals(sortDirection)) {
            notifications =
                notificationRepository.findUnreadAfterAscending(
                    receiverId,
                    cursorInstant,
                    idAfter,
                    pageRequest
                );
        } else {
            notifications =
                notificationRepository.findUnreadAfterDescending(
                    receiverId,
                    cursorInstant,
                    idAfter,
                    pageRequest
                );
        }

        boolean hasNext = notifications.size() > limit;

        List<Notification> page =
            hasNext
                ? notifications.subList(0, limit)
                : notifications;

        List<NotificationDto> data = page.stream()
            .map(NotificationDto::from)
            .toList();

        String nextCursor = null;
        UUID nextIdAfter = null;

        if (hasNext && !page.isEmpty()) {
            Notification lastNotification =
                page.get(page.size() - 1);

            nextCursor =
                lastNotification.getCreatedAt().toString();
            nextIdAfter =
                lastNotification.getId();
        }

        long totalCount =
            notificationRepository
                .countByReceiverIdAndReadAtIsNull(receiverId);

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

    @Transactional
    public void read(
        UUID notificationId,
        UUID receiverId
    ) {
        Notification notification =
            notificationRepository
                .findByIdAndReceiverId(
                    notificationId,
                    receiverId
                )
                .orElseThrow(() ->
                    new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND
                    )
                );

        notification.markAsRead(Instant.now());
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
