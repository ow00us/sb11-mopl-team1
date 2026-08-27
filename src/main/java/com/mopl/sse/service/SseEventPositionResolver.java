package com.mopl.sse.service;

import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SseEventPositionResolver {

    private final NotificationRepository notificationRepository;
    private final DirectMessageRepository directMessageRepository;
    private final ConversationParticipantRepository participantRepository;

    public Optional<SseEventPosition> resolve(
        UUID userId,
        String lastEventId
    ) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return Optional.empty();
        }

        UUID eventId;

        try {
            eventId = UUID.fromString(
                lastEventId
            );
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        Optional<SseEventPosition> notificationPosition =
            notificationRepository
                .findByIdAndReceiverId(
                    eventId,
                    userId
                )
                .map(notification ->
                    new SseEventPosition(
                        notification.getId(),
                        notification.getCreatedAt()
                    )
                );

        if (notificationPosition.isPresent()) {
            return notificationPosition;
        }

        return directMessageRepository
            .findById(eventId)
            .filter(message ->
                !message
                    .getSenderId()
                    .equals(userId)
            )
            .filter(message ->
                participantRepository
                    .existsByConversationIdAndUserId(
                        message.getConversationId(),
                        userId
                    )
            )
            .map(message ->
                new SseEventPosition(
                    message.getId(),
                    message.getCreatedAt()
                )
            );
    }
}
