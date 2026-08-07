package com.mopl.notification.event;

import com.mopl.notification.dto.NotificationDto;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.sse.service.SseEmitterManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class NotificationSseListenerTest {

    private static final UUID NOTIFICATION_ID =
        UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID RECEIVER_ID =
        UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    SseEmitterManager sseEmitterManager;

    @InjectMocks
    NotificationSseListener notificationSseListener;

    @Test
    @DisplayName("알림 생성 이벤트를 수신하면 수신자에게 SSE 알림을 전송")
    void sendNotification_success() {
        // given
        NotificationDto notification =
            new NotificationDto(
                NOTIFICATION_ID,
                Instant.parse(
                    "2026-08-07T01:00:00Z"
                ),
                RECEIVER_ID,
                "새로운 알림",
                "알림 내용",
                NotificationLevel.INFO
            );

        NotificationCreatedEvent event =
            new NotificationCreatedEvent(notification);

        // when
        notificationSseListener.sendNotification(
            event
        );

        // then
        verify(sseEmitterManager).send(
            RECEIVER_ID,
            NOTIFICATION_ID,
            "notifications",
            notification
        );
    }
}
