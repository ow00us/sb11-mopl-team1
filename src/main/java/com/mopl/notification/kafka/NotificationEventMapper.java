package com.mopl.notification.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventContractViolationException;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.KafkaEventContract;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import com.mopl.notification.kafka.payload.DirectMessageCreatedPayload;
import com.mopl.notification.kafka.payload.FollowCreatedPayload;
import com.mopl.notification.kafka.payload.PlaylistSubscriptionCreatedPayload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.Optional;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventMapper {

    private static final int MAX_CONTENT_PREVIEW_LENGTH = 100;
    private static final Set<KafkaEventContract> SUPPORTED_CONTRACTS = EnumSet.of(
            KafkaEventContract.FOLLOW_CREATED,
            KafkaEventContract.PLAYLIST_SUBSCRIPTION_CREATED,
            KafkaEventContract.DIRECT_MESSAGE_CREATED
    );

    private final ObjectMapper objectMapper;
    private final NotificationUserReader notificationUserReader;

    private String normalizeContentPreview(
        String contentPreview,
        UUID eventId
    ) {
        int length = contentPreview.codePointCount(0, contentPreview.length());

        if (length <= MAX_CONTENT_PREVIEW_LENGTH) {
            return contentPreview;
        }

        log.warn(
            "DM contentPreview 길이 초과: "
                + "eventId={}, length={}",
            eventId,
            length
        );

        int endIndex =
            contentPreview.offsetByCodePoints(
                0,
                MAX_CONTENT_PREVIEW_LENGTH
            );

        return contentPreview.substring(
            0,
            endIndex
        );
    }

    public Optional<NotificationCreateCommand> map(
        EventEnvelope envelope
    ) {
        KafkaEventContract contract = validateEnvelope(envelope);
        if (!SUPPORTED_CONTRACTS.contains(contract)) {
            throw new EventContractViolationException(
                "지원하지 않는 알림 이벤트 type·version입니다."
            );
        }

        return switch (contract) {
            case FOLLOW_CREATED -> mapFollow(envelope);
            case PLAYLIST_SUBSCRIPTION_CREATED -> mapPlaylistSubscription(envelope);
            case DIRECT_MESSAGE_CREATED -> mapDirectMessage(envelope);
        };
    }

    public boolean supports(
        String type
    ) {
        return SUPPORTED_CONTRACTS.stream()
            .anyMatch(contract -> contract.type().equals(type));
    }

    public boolean supports(
        String type,
        int version
    ) {
        return KafkaEventContract.find(type, version)
            .filter(SUPPORTED_CONTRACTS::contains)
            .isPresent();
    }

    private Optional<NotificationCreateCommand> mapFollow(
        EventEnvelope envelope
    ) {
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

        if (!notificationUserReader.exists(
            payload.followeeId()
        )) {
            log.info(
                "알림 수신자가 존재하지 않아 생략: "
                    + "eventId={}, receiverId={}",
                envelope.eventId(),
                payload.followeeId()
            );

            return Optional.empty();
        }

        Optional<String> followerName =
            notificationUserReader.findName(
                payload.followerId()
            );

        if (followerName.isEmpty()) {
            log.info(
                "알림 행위자가 존재하지 않아 생략: "
                    + "eventId={}, actorId={}",
                envelope.eventId(),
                payload.followerId()
            );

            return Optional.empty();
        }

        return Optional.of(
            new NotificationCreateCommand(
                payload.followeeId(),
                envelope.eventId(),
                NotificationType.FOLLOW,
                payload.followerId(),
                envelope.aggregateId(),
                "[팔로우] " + followerName.get(),
                followerName.get()
                    + "님이 회원님을 팔로우했습니다.",
                NotificationLevel.INFO
            )
        );
    }

    private Optional<NotificationCreateCommand>
        mapPlaylistSubscription(
            EventEnvelope envelope
        ) {

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

        if (!notificationUserReader.exists(
            payload.playlistOwnerId()
        )) {
            log.info(
                "알림 수신자가 존재하지 않아 생략: "
                    + "eventId={}, receiverId={}",
                envelope.eventId(),
                payload.playlistOwnerId()
            );

            return Optional.empty();
        }

        Optional<String> subscriberName =
            notificationUserReader.findName(
                payload.subscriberId()
            );

        if (subscriberName.isEmpty()) {
            log.info(
                "알림 행위자가 존재하지 않아 생략: "
                    + "eventId={}, actorId={}",
                envelope.eventId(),
                payload.subscriberId()
            );

            return Optional.empty();
        }

        return Optional.of(
            new NotificationCreateCommand(
                payload.playlistOwnerId(),
                envelope.eventId(),
                NotificationType.PLAYLIST_SUBSCRIPTION,
                payload.playlistId(),
                envelope.aggregateId(),
                "[플레이리스트 구독] "
                    + subscriberName.get(),
                subscriberName.get()
                    + "님이 플레이리스트를 구독했습니다.",
                NotificationLevel.INFO
            )
        );
    }

    private Optional<NotificationCreateCommand> mapDirectMessage(
        EventEnvelope envelope
    ) {

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

        String contentPreview =
            normalizeContentPreview(
                payload.contentPreview(),
                envelope.eventId()
            );

        if (!envelope.aggregateId().equals(payload.directMessageId())) {
            throw new EventContractViolationException(
                "aggregateId와 directMessageId가 "
                    + "일치하지 않습니다."
            );
        }

        if (!notificationUserReader.exists(
            payload.receiverId()
        )) {
            log.info(
                "알림 수신자가 존재하지 않아 생략: "
                    + "eventId={}, receiverId={}",
                envelope.eventId(),
                payload.receiverId()
            );

            return Optional.empty();
        }

        Optional<String> senderName =
            notificationUserReader.findName(
                payload.senderId()
            );

        if (senderName.isEmpty()) {
            log.info(
                "알림 행위자가 존재하지 않아 생략: "
                    + "eventId={}, actorId={}",
                envelope.eventId(),
                payload.senderId()
            );

            return Optional.empty();
        }

        return Optional.of(
            new NotificationCreateCommand(
                payload.receiverId(),
                envelope.eventId(),
                NotificationType.DIRECT_MESSAGE,
                payload.conversationId(),
                payload.directMessageId(),
                "[DM] " + senderName.get(),
                contentPreview,
                NotificationLevel.INFO
            )
        );
    }

    private KafkaEventContract validateEnvelope(
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

        return KafkaEventContract.require(
            envelope.type(),
            envelope.version()
        );
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
