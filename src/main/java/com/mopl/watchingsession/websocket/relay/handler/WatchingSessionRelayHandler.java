package com.mopl.watchingsession.websocket.relay.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.realtime.RealtimeMessage;
import com.mopl.global.realtime.RealtimeMessageHandler;
import com.mopl.watchingsession.dto.WatchingSessionChange;
import com.mopl.watchingsession.websocket.broadcast.WatchingSessionBroadcaster;
import com.mopl.watchingsession.websocket.relay.contract.WatchingSessionRealtimeContract;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WatchingSessionRelayHandler implements RealtimeMessageHandler {

    private final ObjectMapper objectMapper;
    private final WatchingSessionBroadcaster broadcaster;

    @Override
    public boolean supports(String eventType) {
        return WatchingSessionRealtimeContract.EVENT_TYPE.equals(eventType);
    }

    @Override
    public void handle(RealtimeMessage relayMessage) {
        WatchingSessionChange change =
            objectMapper.convertValue(relayMessage.payload(), WatchingSessionChange.class);

        String expectedDestination =
            WatchingSessionRealtimeContract.getDestination(change.watchingSessionDto().content().id());

        if (!expectedDestination.equals(relayMessage.destination())) {
            throw new IllegalArgumentException("시청 세션 실시간 메시지의 목적지가 올바르지 않습니다.");
        }

        broadcaster.broadcast(change.watchingSessionDto().content().id(), change);
    }
}
