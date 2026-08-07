package com.mopl.notification.event;

import com.mopl.notification.dto.NotificationDto;
import com.mopl.sse.service.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 알림 SSE Listener 생성
@Component
@RequiredArgsConstructor
public class NotificationSseListener {

    private static final String EVENT_NAME = "notification";

    private final SseEmitterManager sseEmitterManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendNotification(NotificationCreatedEvent event) {
        NotificationDto notification =
            event.notification();

        sseEmitterManager.send(
            notification.receiverId(),
            notification.id(),
            EVENT_NAME,
            notification
        );
    }
}
