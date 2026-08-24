package com.mopl.watchingsession.websocket.broadcast;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.watchingsession.dto.ContentChatDto;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 채팅 구독 직후, 그 세션 하나에만 백로그 메시지를 STOMP MESSAGE 프레임으로 직접 전송합니다.
 *
 * {@code SimpMessagingTemplate.convertAndSend}는 목적지를 구독한 모든 세션에 나갑니다.
 * 백로그는 방금 구독한 세션 하나에만 가야 하므로 이 경로를 쓸 수 없고,
 * sessionId를 채운 프레임을 clientOutboundChannel에 직접 보내는 방식을 그대로 씁니다.
 */
@Slf4j
@Component
public class ContentChatBacklogSender {

    private final ObjectMapper objectMapper;
    private final MessageChannel clientOutboundChannel;

    public ContentChatBacklogSender(
        ObjectMapper objectMapper,
        @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel
    ) {
        this.objectMapper = objectMapper;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    /**
     * 백로그를 오래된 순서 그대로 한 세션에 순서대로 전송합니다. 한 건의 직렬화 실패가 나머지 전송을 막지 않도록 항목 단위로 흡수합니다.
     */
    public void send(String sessionId, String subscriptionId, String destination,
        List<ContentChatDto> backlog) {
        for (ContentChatDto message : backlog) {
            byte[] payload;
            try {
                payload = objectMapper.writeValueAsBytes(message);
            } catch (JsonProcessingException e) {
                log.error("백로그 메시지 직렬화 실패, 이 건은 건너뜁니다. destination={}", destination, e);
                continue;
            }

            StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.MESSAGE);
            accessor.setSessionId(sessionId);
            accessor.setSubscriptionId(subscriptionId);
            accessor.setDestination(destination);
            accessor.setMessageId(UUID.randomUUID().toString());
            accessor.setLeaveMutable(true);

            clientOutboundChannel.send(
                MessageBuilder.createMessage(payload, accessor.getMessageHeaders()));
        }
    }
}
