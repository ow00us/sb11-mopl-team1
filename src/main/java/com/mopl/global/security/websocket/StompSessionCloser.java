package com.mopl.global.security.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 서버 측에서 STOMP 세션을 강제로 종료해야 하는 상황에 사용한다.
 *
 * clientOutboundChannel 로 SimpMessageType.DISCONNECT_ACK 메시지를 전송하면
 * StompSubProtocolHandler 가 이를 처리하며 WebSocketSession 을 close 한다.
 * 이후 SessionDisconnectEvent 가 발행되어 SimpleBrokerMessageHandler 가
 * 해당 세션에 등록된 모든 구독을 자동으로 정리하므로,
 * 브로커 구독 레지스트리를 직접 조작하지 않고도 정합성을 회복할 수 있다.
 *
 * 사용 사례: @EventListener(SessionSubscribeEvent) 에서 브로커 등록 이후 후속
 * 처리(예: WatchingSessionService.start())가 인프라 오류로 실패한 경우,
 * 클라이언트에 ERROR 프레임을 보낸 뒤 이 클래스로 세션을 종료해 브로커에
 * 남은 유령 구독을 정리한다.
 */
@Slf4j
@Component
public class StompSessionCloser {

    private final MessageChannel clientOutboundChannel;

    public StompSessionCloser(
        @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel
    ) {
        this.clientOutboundChannel = clientOutboundChannel;
    }

    /**
     * 원본 STOMP 메시지의 sessionId 를 이어받아 서버 주도 DISCONNECT_ACK 를 발행한다.
     * 대응하는 WebSocket 연결이 close 되며 SessionDisconnectEvent 가 자동으로 발행된다.
     *
     * @param originalMessage 종료 대상 세션의 원본 STOMP 메시지 (SUBSCRIBE 등)
     */
    public void close(Message<?> originalMessage) {
        StompHeaderAccessor originalAccessor = StompHeaderAccessor.wrap(originalMessage);
        String sessionId = originalAccessor.getSessionId();

        if (sessionId == null) {
            log.warn("STOMP 세션 종료 요청에 sessionId 가 없어 무시합니다.");
            return;
        }

        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT_ACK);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);

        Message<byte[]> disconnectAck = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        clientOutboundChannel.send(disconnectAck);
    }
}
