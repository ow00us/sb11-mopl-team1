package com.mopl.directmessage.websocket;

import com.mopl.directmessage.dto.DirectMessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
        messagingTemplate.convertAndSend(
            destination,
            message
        );
    }
}
