package com.mopl.watchingsession.websocket.relay.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.realtime.RealtimeMessage;
import com.mopl.global.realtime.RealtimeMessageHandler;
import com.mopl.watchingsession.service.ContentChatService;
import com.mopl.watchingsession.websocket.relay.payload.ContentChatRelayPayload;
import com.mopl.watchingsession.websocket.relay.contract.ContentChatRealtimeContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentChatRelayHandler implements RealtimeMessageHandler {

    private final ObjectMapper objectMapper;
    private final ContentChatService contentChatService;

    @Override
    public boolean supports(String eventType) {
        return ContentChatRealtimeContract.EVENT_TYPE.equals(eventType);
    }

    @Override
    public void handle(RealtimeMessage relayMessage) {
        ContentChatRelayPayload payload = objectMapper.convertValue(relayMessage.payload(), ContentChatRelayPayload.class);

        String expectedDestination = ContentChatRealtimeContract.getDestination(payload.contentId());

        if (!expectedDestination.equals(relayMessage.destination())) {
            throw new IllegalArgumentException("콘텐츠 채팅 실시간 메시지의 목적지가 올바르지 않습니다.");
        }

        contentChatService.broadcast(payload.contentId(), payload.chat());
    }

}
