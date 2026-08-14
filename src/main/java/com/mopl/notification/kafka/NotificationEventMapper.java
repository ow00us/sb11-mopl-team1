package com.mopl.notification.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventContractViolationException;
import com.mopl.global.event.EventEnvelope;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import com.mopl.notification.kafka.payload.DirectMessageCreatedPayload;
import com.mopl.notification.kafka.payload.FollowCreatedPayload;
import com.mopl.notification.kafka.payload.PlaylistSubscriptionCreatedPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
@Component
@RequiredArgsConstructor
public class NotificationEventMapper {

    private static final int SUPPORTED_VERSION = 1;
    private static final String FOLLOW_CREATED = "follow.created";
    private static final String PLAYLIST_SUBSCRIPTION_CREATED = "playlist.subscription.created";
    private static final String DIRECT_MESSAGE_CREATED = "direct-message.created";
    private static final int MAX_CONTENT_PREVIEW_LENGTH = 100;

    private final ObjectMapper objectMapper;
    private final NotificationUserReader notificationUserReader;

    public NotificationCreateCommand map(EventEnvelope envelope) {
        validateEnvelope(envelope);

        return switch (envelope.type()) {
            case FOLLOW_CREATED -> mapFollow(envelope);
            case PLAYLIST_SUBSCRIPTION_CREATED -> mapPlaylistSubscription(envelope);
            case DIRECT_MESSAGE_CREATED -> mapDirectMessage(envelope);
            default -> throw new EventContractViolationException(
                "지원하지 않는 이벤트 타입입니다."
            );
        };
    }

    private NotificationCreateCommand mapFollow(EventEnvelope envelope) {
        FollowCreatedPayload payload =
            convertPayload(
                envelope.payload(),
                FollowCreatedPayload.class
            );

        requirePresent(
            payload.followerId(),
            "followerId"
        );

        requirePresent(
            payload.followeeId(),
            "followeeId"
        );

        String followerName =
            notificationUserReader.getName(
                payload.followerId()
            );

        return new NotificationCreateCommand(
            payload.followeeId(),
            envelope.eventId(),
            NotificationType.FOLLOW,
            payload.followerId(),
            envelope.aggregateId(),
            "[팔로우] " + followerName,
            followerName + "님이 회원님을 팔로우했습니다.",
            NotificationLevel.INFO
            );
    }

    private NotificationCreateCommand mapPlaylistSubscription(EventEnvelope envelope) {

        PlaylistSubscriptionCreatedPayload payload =
            convertPayload(
                envelope.payload(),
                PlaylistSubscriptionCreatedPayload.class
            );

        requirePresent(
            payload.playlistId(),
            "playlistId"
        );

        requirePresent(
            payload.playlistOwnerId(),
            "playlistOwnerId"
        );

        requirePresent(
            payload.subscriberId(),
            "subscriberId"
        );

        String subscriberName =
            notificationUserReader.getName(
                payload.subscriberId()
            );

        return new NotificationCreateCommand(
            payload.playlistOwnerId(),
            envelope.eventId(),
            NotificationType.PLAYLIST_SUBSCRIPTION,
            payload.playlistId(),
            envelope.aggregateId(),
            "[플레이리스트 구독] " + subscriberName,
            subscriberName + "님이 플레이리스트를 구독했습니다.",
            NotificationLevel.INFO
        );
    }

    private NotificationCreateCommand mapDirectMessage(EventEnvelope envelope) {

        DirectMessageCreatedPayload payload =
            convertPayload(
                envelope.payload(),
                DirectMessageCreatedPayload.class
            );

        requirePresent(
            payload.directMessageId(),
            "directMessageId"
        );

        requirePresent(
            payload.conversationId(),
            "conversationId"
        );

        requirePresent(
            payload.senderId(),
            "senderId"
        );

        requirePresent(
            payload.receiverId(),
            "receiverId"
        );

        requireText(
            payload.contentPreview(),
            "contentPreview"
        );

        if (payload.contentPreview().length() > MAX_CONTENT_PREVIEW_LENGTH) {

            throw new EventContractViolationException(
                "contentPreview는 100자 이하여야 합니다."
            );
        }

        if (!envelope.aggregateId().equals(payload.directMessageId())) {
            throw new EventContractViolationException(
                "aggregateId와 directMessageId가 "
                    + "일치하지 않습니다."
            );
        }

        String senderName =
            notificationUserReader.getName(
                payload.senderId()
            );

        return new NotificationCreateCommand(
            payload.receiverId(),
            envelope.eventId(),
            NotificationType.DIRECT_MESSAGE,
            payload.conversationId(),
            payload.directMessageId(),
            "[DM] " + senderName,
            payload.contentPreview(),
            NotificationLevel.INFO
        );
    }

    private void validateEnvelope(
        EventEnvelope envelope
    ) {
        if (envelope == null) {
            throw new EventContractViolationException(
                "EventEnvelope이 없습니다."
            );
        }

        requirePresent(
            envelope.eventId(),
            "eventId"
        );

        requireText(
            envelope.type(),
            "type"
        );

        requirePresent(
            envelope.occurredAt(),
            "occurredAt"
        );

        requirePresent(
            envelope.aggregateId(),
            "aggregateId"
        );

        if (envelope.version()
            != SUPPORTED_VERSION) {

            throw new EventContractViolationException(
                "지원하지 않는 이벤트 version입니다."
            );
        }

        JsonNode payload =
            envelope.payload();

        if (payload == null
            || payload.isNull()
            || payload.isMissingNode()
            || !payload.isObject()) {

            throw new EventContractViolationException(
                "payload가 올바른 객체가 아닙니다."
            );
        }
    }

    private <T> T convertPayload(
        JsonNode payload,
        Class<T> payloadType
    ) {
        try {
            return objectMapper.treeToValue(
                payload,
                payloadType
            );
        } catch (JsonProcessingException exception) {
            throw new EventContractViolationException(
                "payload를 변환할 수 없습니다.",
                exception
            );
        }
    }

    private void requirePresent(
        Object value,
        String field
    ) {
        if (value == null) {
            throw new EventContractViolationException(
                field + "가 없습니다."
            );
        }
    }

    private void requireText(
        String value,
        String field
    ) {
        if (!StringUtils.hasText(value)) {
            throw new EventContractViolationException(
                field + "가 비어 있습니다."
            );
        }
    }
}
