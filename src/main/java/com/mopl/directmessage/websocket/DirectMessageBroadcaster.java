package com.mopl.directmessage.websocket;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.dto.DirectMessageReadEvent;
import com.mopl.directmessage.dto.DirectMessageRealtimeEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectMessageBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(
        UUID conversationId,
        DirectMessageDto message
    ) {
        String destination =
            DirectMessageRealtimeContract.destination(
                conversationId
            );

        broadcast(
            destination,
            message
        );
    }

    void broadcast(
        String destination,
        DirectMessageDto message
    ) {
        send(
            destination,
            DirectMessageRealtimeEvent.created(
                message
            )
        );
    }

    public void broadcastRead(
        UUID conversationId,
        DirectMessageReadEvent readEvent
    ) {
        String destination =
            DirectMessageRealtimeContract.destination(
                conversationId
            );

        broadcastRead(
            destination,
            readEvent
        );
    }

    void broadcastRead(
        String destination,
        DirectMessageReadEvent readEvent
    ) {
        send(
            destination,
            DirectMessageRealtimeEvent.read(
                readEvent
            )
        );
    }

    private void send(
        String destination,
        DirectMessageRealtimeEvent<?> event
    ) {
        messagingTemplate.convertAndSend(
            destination,
            event
        );
    }
}
