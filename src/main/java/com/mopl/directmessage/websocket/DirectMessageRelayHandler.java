package com.mopl.directmessage.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.dto.DirectMessageReadEvent;
import com.mopl.global.realtime.RealtimeMessage;
import com.mopl.global.realtime.RealtimeMessageHandler;
import java.util.UUID;
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
        return DirectMessageRealtimeContract
            .CREATED_EVENT_TYPE
            .equals(eventType)
            || DirectMessageRealtimeContract
            .READ_EVENT_TYPE
            .equals(eventType);
    }

    @Override
    public void handle(
        RealtimeMessage relayMessage
    ) {
        if (
            DirectMessageRealtimeContract
                .CREATED_EVENT_TYPE
                .equals(relayMessage.eventType())
        ) {
            handleCreated(relayMessage);
            return;
        }

        if (
            DirectMessageRealtimeContract
                .READ_EVENT_TYPE
                .equals(relayMessage.eventType())
        ) {
            handleRead(relayMessage);
        }
    }

    private void handleCreated(
        RealtimeMessage relayMessage
    ) {
        DirectMessageDto message =
            objectMapper.convertValue(
                relayMessage.payload(),
                DirectMessageDto.class
            );

        validateDestination(
            relayMessage.destination(),
            message.conversationId()
        );

        broadcaster.broadcast(
            relayMessage.destination(),
            message
        );
    }

    private void handleRead(
        RealtimeMessage relayMessage
    ) {
        DirectMessageReadEvent readEvent =
            objectMapper.convertValue(
                relayMessage.payload(),
                DirectMessageReadEvent.class
            );

        validateDestination(
            relayMessage.destination(),
            readEvent.conversationId()
        );

        broadcaster.broadcastRead(
            relayMessage.destination(),
            readEvent
        );
    }

    private void validateDestination(
        String destination,
        UUID conversationId
    ) {
        String expectedDestination =
            DirectMessageRealtimeContract.destination(
                conversationId
            );

        if (!expectedDestination.equals(destination)) {
            throw new IllegalArgumentException(
                "DM 실시간 메시지의 목적지가 올바르지 않습니다."
            );
        }
    }
}
