package com.mopl.directmessage.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.global.realtime.RealtimeMessage;
import com.mopl.global.realtime.RealtimeMessageHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectMessageRelayHandler
    implements RealtimeMessageHandler {

    private final ObjectMapper objectMapper;
    private final DirectMessageBroadcaster broadcaster;

    @Override
    public boolean supports(
        String eventType
    ) {
        return DirectMessageRealtimeContract.EVENT_TYPE
            .equals(eventType);
    }

    @Override
    public void handle(
        RealtimeMessage relayMessage
    ) {
        DirectMessageDto message =
            objectMapper.convertValue(
                relayMessage.payload(),
                DirectMessageDto.class
            );

        String expectedDestination =
            DirectMessageRealtimeContract.destination(
                message.conversationId()
            );

        if (!expectedDestination.equals(
            relayMessage.destination()
        )) {
            throw new IllegalArgumentException(
                "DM 실시간 메시지의 목적지가 올바르지 않습니다."
            );
        }

        broadcaster.broadcast(
            relayMessage.destination(),
            message
        );
    }
}
