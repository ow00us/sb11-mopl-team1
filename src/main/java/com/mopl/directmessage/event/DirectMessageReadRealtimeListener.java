package com.mopl.directmessage.event;

import com.mopl.directmessage.dto.DirectMessageReadEvent;
import com.mopl.directmessage.websocket.DirectMessageBroadcaster;
import com.mopl.directmessage.websocket.DirectMessageRelayPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectMessageReadRealtimeListener {

    private final DirectMessageBroadcaster broadcaster;
    private final DirectMessageRelayPublisher relayPublisher;

    @TransactionalEventListener(
        phase = TransactionPhase.AFTER_COMMIT
    )
    public void sendReadEvent(
        DirectMessageReadEvent event
    ) {
        try {
            broadcaster.broadcastRead(
                event.conversationId(),
                event
            );
        } catch (RuntimeException exception) {
            log.warn(
                "DM 읽음 WebSocket 전송에 실패했습니다. "
                    + "conversationId={}, lastReadMessageId={}",
                event.conversationId(),
                event.lastReadMessageId(),
                exception
            );
        }

        relayPublisher.publishRead(
            event.conversationId(),
            event
        );
    }
}
