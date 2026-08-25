package com.mopl.sse.service;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.repository.DirectMessageReplayProjection;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.global.common.UserSummary;
import com.mopl.notification.dto.NotificationDto;
import com.mopl.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SseReplayService {

    private static final int REPLAY_LIMIT = 100;

    private static final String NOTIFICATION_EVENT_NAME =
        "notifications";

    private static final String DIRECT_MESSAGE_EVENT_NAME =
        "direct-messages";

    private final SseEmitterManager sseEmitterManager;
    private final SseEventPositionResolver
        eventPositionResolver;
    private final NotificationRepository
        notificationRepository;
    private final DirectMessageRepository
        directMessageRepository;

    public SseEmitter subscribe(
        UUID userId,
        String lastEventId
    ) {
        SseEmitter emitter =
            sseEmitterManager.subscribe(
                userId
            );

        eventPositionResolver
            .resolve(
                userId,
                lastEventId
            )
            .ifPresent(position ->
                replay(
                    userId,
                    emitter,
                    position
                )
            );

        return emitter;
    }

    private void replay(
        UUID userId,
        SseEmitter emitter,
        SseEventPosition position
    ) {
        PageRequest pageRequest =
            PageRequest.of(
                0,
                REPLAY_LIMIT
            );

        List<SseReplayEvent> notifications =
            notificationRepository
                .findAllForReplay(
                    userId,
                    position.createdAt(),
                    position.eventId(),
                    pageRequest
                )
                .stream()
                .map(notification ->
                    new SseReplayEvent(
                        notification.getId(),
                        notification.getCreatedAt(),
                        NOTIFICATION_EVENT_NAME,
                        NotificationDto.from(
                            notification
                        )
                    )
                )
                .toList();

        List<SseReplayEvent> directMessages =
            directMessageRepository
                .findAllReceivedForReplay(
                    userId,
                    position.createdAt(),
                    position.eventId(),
                    pageRequest
                )
                .stream()
                .map(this::toReplayEvent)
                .toList();

        Stream.concat(
                notifications.stream(),
                directMessages.stream()
            )
            .sorted(
                Comparator
                    .comparing(
                        SseReplayEvent::createdAt
                    )
                    .thenComparing(
                        SseReplayEvent::eventId
                    )
            )
            .limit(REPLAY_LIMIT)
            .forEach(event ->
                sseEmitterManager.send(
                    userId,
                    emitter,
                    event.eventId(),
                    event.eventName(),
                    event.data()
                )
            );
    }

    private SseReplayEvent toReplayEvent(
        DirectMessageReplayProjection message
    ) {
        UserSummary sender =
            new UserSummary(
                message.getSenderId(),
                message.getSenderName(),
                message.getSenderProfileImageUrl()
            );

        UserSummary receiver =
            new UserSummary(
                message.getReceiverId(),
                message.getReceiverName(),
                message.getReceiverProfileImageUrl()
            );

        DirectMessageDto data =
            new DirectMessageDto(
                message.getId(),
                message.getConversationId(),
                message.getCreatedAt(),
                message.getMessageSequence(),
                sender,
                receiver,
                message.getContent()
            );

        return new SseReplayEvent(
            message.getId(),
            message.getCreatedAt(),
            DIRECT_MESSAGE_EVENT_NAME,
            data
        );
    }

    private record SseReplayEvent(
        UUID eventId,
        Instant createdAt,
        String eventName,
        Object data
    ) {
    }
}
