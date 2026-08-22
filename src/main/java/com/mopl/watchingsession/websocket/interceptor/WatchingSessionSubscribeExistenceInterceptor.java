package com.mopl.watchingsession.websocket.interceptor;

import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.websocket.StompErrorFrameSender;
import com.mopl.watchingsession.presence.ContentExistenceCache;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * watch·chat 토픽 SUBSCRIBE 시점에 콘텐츠 존재 여부를 preSend()에서 미리 검증한다.
 *
 * 이 인터셉터는 "존재 검증(existence check)"만 수행하며 "인가(authorization)"는
 * 하지 않는다 - 요청자가 누구인지는 검사하지 않고, 콘텐츠 자체가 있는지만 확인한다.
 *
 * 기존에는 이 검증을 @EventListener(SessionSubscribeEvent)에서 수행했는데, 이 이벤트는
 * StompSubProtocolHandler가 clientInboundChannel로 SUBSCRIBE를 보낸 뒤, 브로커
 * (SimpleBrokerMessageHandler)가 이미 구독을 등록한 다음에 부수적으로 발행된다.
 * 그 시점에 BusinessException을 잡아도 이미 등록된 브로커 구독을 되돌릴 방법이 없고,
 * Spring 6.2.2의 ApplicationEventMulticaster.publishEvent()는 리스너 예외를 잡아
 * 로그만 남기므로 WebSocketStompErrorHandler(채널 레벨)에도 도달하지 못한다.
 *
 * preSend()에서 예외를 던지면, StompSubProtocolHandler가 이를 잡아 브로커에 SUBSCRIBE가
 * 전달되기 전에 처리를 중단하므로, 구독 자체가 브로커에 등록되지 않는다.
 */
@Component
@RequiredArgsConstructor
public class WatchingSessionSubscribeExistenceInterceptor implements ChannelInterceptor {

    private static final Pattern CONTENT_SUBSCRIBE_DESTINATION_PATTERN =
        Pattern.compile("^/sub/contents/([^/]+)/(?:watch|chat)$");

    private final StompErrorFrameSender errorFrameSender;
    private final ContentExistenceCache contentExistenceCache;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message,
            StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }

        Matcher matcher = CONTENT_SUBSCRIBE_DESTINATION_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return message;
        }

        UUID contentId = parseContentId(matcher.group(1));

        if (contentId != null && contentExistenceCache.exists(contentId)) {
            return message;
        }

        // q브코러에 이 SUBSCRIBE가 전달되지 않도록 여기서 중단하되 연결은 유지한 채 클라이언트에게 실패를 직접 알린다
        errorFrameSender.send(message, "BusinessException", ErrorCode.CONTENT_NOT_FOUND, ErrorCode.CONTENT_NOT_FOUND.getMessage(), Map.of());

        return null;
    }

    private UUID parseContentId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

