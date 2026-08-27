package com.mopl.directmessage.websocket;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.dto.DirectMessageReadEvent;
import com.mopl.global.realtime.RealtimeRelayPublisher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
            DirectMessageRealtimeContract
                .CREATED_EVENT_TYPE,
            destination,
            message
        );
    }

    public boolean publishRead(
        UUID conversationId,
        DirectMessageReadEvent readEvent
    ) {
        String destination =
            DirectMessageRealtimeContract.destination(
                conversationId
            );

        return relayPublisher.publish(
            DirectMessageRealtimeContract
                .READ_EVENT_TYPE,
            destination,
            readEvent
        );
    }
}
