package com.mopl.watchingsession.websocket.relay.publisher;

import com.mopl.global.realtime.RealtimeRelayPublisher;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.websocket.relay.payload.ContentChatRelayPayload;
import com.mopl.watchingsession.websocket.relay.contract.ContentChatRealtimeContract;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContentChatRelayPublisher {

    private final RealtimeRelayPublisher relayPublisher;

    public boolean publish(UUID contentId, ContentChatDto chatDto) {
        String destination = ContentChatRealtimeContract.getDestination(contentId);

        return relayPublisher.publish(
            ContentChatRealtimeContract.EVENT_TYPE,
            destination,
            new ContentChatRelayPayload(contentId, chatDto)
        );
    }

}
