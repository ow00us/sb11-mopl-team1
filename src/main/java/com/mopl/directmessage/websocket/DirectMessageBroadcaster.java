package com.mopl.directmessage.websocket;

import com.mopl.directmessage.dto.DirectMessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DirectMessageBroadcaster {

    private static final String DESTINATION =
        "/sub/conversations/%s/direct-messages";

    private final SimpMessagingTemplate messagingTemplate;

    public void broadcast(
        UUID conversationId,
        DirectMessageDto message
    ) {
        String destination =
            DESTINATION.formatted(conversationId);

        messagingTemplate.convertAndSend(
            destination,
            message
        );
    }
}
