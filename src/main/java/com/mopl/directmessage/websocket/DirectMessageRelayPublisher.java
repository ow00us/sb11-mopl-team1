package com.mopl.directmessage.websocket;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.global.realtime.RealtimeRelayPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DirectMessageRelayPublisher {

    private final RealtimeRelayPublisher relayPublisher;

    public boolean publish(
        UUID conversationId,
        DirectMessageDto message
    ) {
        String destination =
            DirectMessageRealtimeContract.destination(
                conversationId
            );

        return relayPublisher.publish(
            DirectMessageRealtimeContract.EVENT_TYPE,
            destination,
            message
        );
    }
}
