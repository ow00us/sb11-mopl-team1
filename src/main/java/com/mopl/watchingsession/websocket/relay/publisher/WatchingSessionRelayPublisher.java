package com.mopl.watchingsession.websocket.relay.publisher;

import com.mopl.global.realtime.RealtimeRelayPublisher;
import com.mopl.watchingsession.dto.WatchingSessionChange;
import com.mopl.watchingsession.websocket.relay.contract.WatchingSessionRealtimeContract;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WatchingSessionRelayPublisher {

    private final RealtimeRelayPublisher relayPublisher;

    public boolean publish(UUID contentId, WatchingSessionChange change) {
        String destination = WatchingSessionRealtimeContract.getDestination(contentId);

        return relayPublisher.publish(
            WatchingSessionRealtimeContract.EVENT_TYPE,
            destination,
            change
        );
    }
}
