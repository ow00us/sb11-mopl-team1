package com.mopl.directmessage.event;

import com.mopl.directmessage.dto.DirectMessageCreatedEvent;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.websocket.DirectMessageSubscriptionRegistry;
import com.mopl.sse.service.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class DirectMessageSseListener {

    private static final String EVENT_NAME =
        "direct-messages";

    private final DirectMessageSubscriptionRegistry
        subscriptionRegistry;

    private final SseEmitterManager sseEmitterManager;

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT
    )
    public void sendDirectMessage(
        DirectMessageCreatedEvent event
    ) {
        DirectMessageDto message =
            event.directMessage();

        if (
            subscriptionRegistry.isActive(
                message.receiver().userId(),
                message.conversationId()
            )
        ) {
            return;
        }

        sseEmitterManager.send(
            message.receiver().userId(),
            message.id(),
            EVENT_NAME,
            message
        );
    }
}
