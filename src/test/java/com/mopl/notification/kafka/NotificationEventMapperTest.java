package com.mopl.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventContractViolationException;
import com.mopl.global.event.EventEnvelope;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationEventMapperTest {

    private static final UUID EVENT_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID AGGREGATE_ID = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    private static final Instant OCCURRED_AT =
        Instant.parse("2026-08-14T01:00:00Z");

    @Mock
    NotificationUserReader notificationUserReader;

    ObjectMapper objectMapper;

    NotificationEventMapper notificationEventMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        notificationEventMapper =
            new NotificationEventMapper(
                objectMapper,
                notificationUserReader
            );
    }

    @Test
    @DisplayName("팔로우 이벤트를 알림 생성 명령으로 변환")
    void map_followCreated_success() {
        // given
        UUID followerId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID followeeId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        when(
            notificationUserReader.exists(followeeId)
        ).thenReturn(true);

        when(
            notificationUserReader.findName(followerId)
        ).thenReturn(Optional.of("팔로워"));

        EventEnvelope envelope = envelope(
            "follow.created",
            AGGREGATE_ID,
            Map.of(
                "followerId",
                followerId,
                "followeeId",
                followeeId
            )
        );

        // when
        NotificationCreateCommand result =
            notificationEventMapper.map(envelope)
                .orElseThrow();

        // then
        assertThat(result.receiverId())
            .isEqualTo(followeeId);

        assertThat(result.sourceEventId())
            .isEqualTo(EVENT_ID);

        assertThat(result.type())
            .isEqualTo(NotificationType.FOLLOW);

        assertThat(result.resourceId())
            .isEqualTo(followerId);

        assertThat(result.sourceEntityId())
            .isEqualTo(AGGREGATE_ID);

        assertThat(result.title())
            .isEqualTo("[팔로우] 팔로워");

        assertThat(result.content())
            .isEqualTo(
                "팔로워님이 회원님을 팔로우했습니다."
            );

        assertThat(result.level())
            .isEqualTo(NotificationLevel.INFO);
    }

    @Test
    @DisplayName("플레이리스트 구독 이벤트를 알림 생성 명령으로 변환")
    void map_playlistSubscriptionCreated_success() {
        // given
        UUID playlistId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID playlistOwnerId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        UUID subscriberId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        when(
            notificationUserReader.exists(playlistOwnerId)
        ).thenReturn(true);

        when(
            notificationUserReader.findName(subscriberId)
        ).thenReturn(Optional.of("구독자"));

        EventEnvelope envelope = envelope(
            "playlist.subscription.created",
            AGGREGATE_ID,
            Map.of(
                "playlistId",
                playlistId,
                "playlistOwnerId",
                playlistOwnerId,
                "subscriberId",
                subscriberId
            )
        );

        // when
        NotificationCreateCommand result =
            notificationEventMapper.map(envelope)
                .orElseThrow();

        // then
        assertThat(result.receiverId())
            .isEqualTo(playlistOwnerId);

        assertThat(result.type())
            .isEqualTo(
                NotificationType.PLAYLIST_SUBSCRIPTION
            );

        assertThat(result.resourceId())
            .isEqualTo(playlistId);

        assertThat(result.sourceEntityId())
            .isEqualTo(AGGREGATE_ID);

        assertThat(result.title())
            .isEqualTo(
                "[플레이리스트 구독] 구독자"
            );

        assertThat(result.content())
            .isEqualTo(
                "구독자님이 플레이리스트를 구독했습니다."
            );
    }

    @Test
    @DisplayName("DM 생성 이벤트를 알림 생성 명령으로 변환")
    void map_directMessageCreated_success() {
        // given
        UUID conversationId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID senderId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        UUID receiverId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        when(
            notificationUserReader.exists(receiverId)
        ).thenReturn(true);

        when(
            notificationUserReader.findName(senderId)
        ).thenReturn(Optional.of("발신자"));

        EventEnvelope envelope = envelope(
            "direct-message.created",
            AGGREGATE_ID,
            Map.of(
                "directMessageId",
                AGGREGATE_ID,
                "conversationId",
                conversationId,
                "senderId",
                senderId,
                "receiverId",
                receiverId,
                "contentPreview",
                "안녕하세요"
            )
        );

        // when
        NotificationCreateCommand result =
            notificationEventMapper.map(envelope)
                .orElseThrow();

        // then
        assertThat(result.receiverId())
            .isEqualTo(receiverId);

        assertThat(result.type())
            .isEqualTo(NotificationType.DIRECT_MESSAGE);

        assertThat(result.resourceId())
            .isEqualTo(conversationId);

        assertThat(result.sourceEntityId())
            .isEqualTo(AGGREGATE_ID);

        assertThat(result.title())
            .isEqualTo("[DM] 발신자");

        assertThat(result.content())
            .isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("지원하지 않는 이벤트 타입이면 변환에 실패")
    void map_unsupportedType_fails() {
        // given
        EventEnvelope envelope = envelope(
            "unsupported.created",
            AGGREGATE_ID,
            Map.of(
                "value",
                "test"
            )
        );

        // when & then
        assertThatThrownBy(() ->
            notificationEventMapper.map(envelope)
        ).isInstanceOf(
            EventContractViolationException.class
        );
    }

    @Test
    @DisplayName("알림 소비자가 지원하는 이벤트 type과 version을 함께 판정")
    void supports_typeAndVersion() {
        assertThat(notificationEventMapper.supports("follow.created", 1)).isTrue();
        assertThat(notificationEventMapper.supports("follow.created", 2)).isFalse();
        assertThat(notificationEventMapper.supports("unsupported.created", 1)).isFalse();
    }

    @Test
    @DisplayName("지원하지 않는 이벤트 버전이면 변환에 실패")
    void map_unsupportedVersion_fails() {
        // given
        EventEnvelope envelope =
            new EventEnvelope(
                EVENT_ID,
                "follow.created",
                2,
                OCCURRED_AT,
                AGGREGATE_ID,
                objectMapper.valueToTree(
                    Map.of(
                        "followerId",
                        UUID.randomUUID(),
                        "followeeId",
                        UUID.randomUUID()
                    )
                )
            );

        // when & then
        assertThatThrownBy(() ->
            notificationEventMapper.map(envelope)
        ).isInstanceOf(
            EventContractViolationException.class
        );
    }

    @Test
    @DisplayName("DM 필수 payload가 누락되면 변환에 실패")
    void map_directMessageMissingReceiver_fails() {
        // given
        EventEnvelope envelope = envelope(
            "direct-message.created",
            AGGREGATE_ID,
            Map.of(
                "directMessageId",
                AGGREGATE_ID,
                "conversationId",
                UUID.randomUUID(),
                "senderId",
                UUID.randomUUID(),
                "contentPreview",
                "안녕하세요"
            )
        );

        // when & then
        assertThatThrownBy(() ->
            notificationEventMapper.map(envelope)
        ).isInstanceOf(
            EventContractViolationException.class
        );
    }

    @Test
    @DisplayName("팔로우 행위자가 없으면 알림 생성을 생략")
    void map_followActorMissing_skips() {
        // given
        UUID followerId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID followeeId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        when(
            notificationUserReader.exists(followeeId)
        ).thenReturn(true);

        when(
            notificationUserReader.findName(followerId)
        ).thenReturn(Optional.empty());

        EventEnvelope envelope = envelope(
            "follow.created",
            AGGREGATE_ID,
            Map.of(
                "followerId",
                followerId,
                "followeeId",
                followeeId
            )
        );

        // when
        Optional<NotificationCreateCommand> result =
            notificationEventMapper.map(envelope);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("DM 수신자가 없으면 알림 생성을 생략")
    void map_directMessageReceiverMissing_skips() {
        // given
        UUID conversationId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID senderId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        UUID receiverId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        when(
            notificationUserReader.exists(receiverId)
        ).thenReturn(false);

        EventEnvelope envelope = envelope(
            "direct-message.created",
            AGGREGATE_ID,
            Map.of(
                "directMessageId",
                AGGREGATE_ID,
                "conversationId",
                conversationId,
                "senderId",
                senderId,
                "receiverId",
                receiverId,
                "contentPreview",
                "안녕하세요"
            )
        );

        // when
        Optional<NotificationCreateCommand> result =
            notificationEventMapper.map(envelope);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("DM 미리보기가 100자를 초과하면 축약")
    void map_contentPreviewTooLong_truncates() {
        // given
        UUID conversationId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID senderId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        UUID receiverId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        String contentPreview = "가".repeat(101);

        when(
            notificationUserReader.exists(receiverId)
        ).thenReturn(true);

        when(
            notificationUserReader.findName(senderId)
        ).thenReturn(Optional.of("발신자"));

        EventEnvelope envelope = envelope(
            "direct-message.created",
            AGGREGATE_ID,
            Map.of(
                "directMessageId",
                AGGREGATE_ID,
                "conversationId",
                conversationId,
                "senderId",
                senderId,
                "receiverId",
                receiverId,
                "contentPreview",
                contentPreview
            )
        );

        // when
        NotificationCreateCommand result =
            notificationEventMapper.map(envelope)
                .orElseThrow();

        // then
        assertThat(
            result.content().codePointCount(
                0,
                result.content().length()
            )
        ).isEqualTo(100);

        assertThat(result.content())
            .isEqualTo("가".repeat(100));
    }

    private EventEnvelope envelope(
        String type,
        UUID aggregateId,
        Map<String, Object> payload
    ) {
        return new EventEnvelope(
            EVENT_ID,
            type,
            1,
            OCCURRED_AT,
            aggregateId,
            objectMapper.valueToTree(payload)
        );
    }
}
