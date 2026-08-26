package com.mopl.notification.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.EventContractViolationException;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import com.mopl.notification.service.NotificationService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationKafkaListenerTest {

    private static final UUID EVENT_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID AGGREGATE_ID = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    @Mock
    NotificationEventMapper notificationEventMapper;

    @Mock
    NotificationService notificationService;

    @InjectMocks
    NotificationKafkaListener notificationKafkaListener;

    @Test
    @DisplayName("Kafka 이벤트를 알림 생성 명령으로 변환해 Service에 전달")
    void consume_success() {
        // given
        EventEnvelope envelope =
            new EventEnvelope(
                EVENT_ID,
                "direct-message.created",
                1,
                Instant.parse(
                    "2026-08-14T01:00:00Z"
                ),
                AGGREGATE_ID,
                null
            );

        NotificationCreateCommand command =
            new NotificationCreateCommand(
                UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
                ),
                EVENT_ID,
                NotificationType.DIRECT_MESSAGE,
                UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
                ),
                AGGREGATE_ID,
                "[DM] 발신자",
                "안녕하세요",
                NotificationLevel.INFO
            );

        when(
            notificationEventMapper.supports(
                envelope.type()
            )
        ).thenReturn(true);

        when(
            notificationEventMapper.supports(
                envelope.type(),
                envelope.version()
            )
        ).thenReturn(true);

        when(
            notificationEventMapper.map(envelope)
        ).thenReturn(Optional.of(command));

        when(
            notificationService.createIfAbsent(command)
        ).thenReturn(true);

        // when
        notificationKafkaListener.consume(envelope);

        // then
        verify(notificationEventMapper)
            .map(envelope);

        verify(notificationService)
            .createIfAbsent(command);
    }

    @Test
    @DisplayName("알림 대상이 아닌 Kafka 이벤트는 정상적으로 건너뜀")
    void consume_unsupportedType_skips() {
        // given
        EventEnvelope envelope =
            new EventEnvelope(
                EVENT_ID,
                "playlist.created",
                1,
                Instant.parse(
                    "2026-08-14T01:00:00Z"
                ),
                AGGREGATE_ID,
                null
            );

        when(
            notificationEventMapper.supports(
                envelope.type()
            )
        ).thenReturn(false);

        // when
        notificationKafkaListener.consume(envelope);

        // then
        verify(notificationEventMapper)
            .supports(envelope.type());

        verify(notificationEventMapper, never())
            .map(envelope);

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("알림 타입이지만 지원하지 않는 버전이면 계약 위반으로 처리")
    void consume_unsupportedVersion_fails() {
        EventEnvelope envelope = new EventEnvelope(
            EVENT_ID,
            "direct-message.created",
            2,
            Instant.parse("2026-08-14T01:00:00Z"),
            AGGREGATE_ID,
            null
        );

        when(notificationEventMapper.supports(envelope.type())).thenReturn(true);
        when(notificationEventMapper.supports(envelope.type(), envelope.version()))
            .thenReturn(false);

        assertThatThrownBy(
            () -> notificationKafkaListener.consume(envelope)
        ).isInstanceOf(EventContractViolationException.class);

        verify(notificationEventMapper, never()).map(envelope);
        verifyNoInteractions(notificationService);
    }
}
