package com.mopl.global.security.websocket;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.security.Principal;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * 인증을 마친 STOMP 세션의 구독·송신 목적지를 공통 계약에 맞게 제한합니다.
 *
 * 클라이언트 송신은 애플리케이션 prefix인 /pub 아래의 명시된 경로만 허용하고,
 * 브로커 prefix인 /sub로 직접 송신하는 요청은 항상 거부합니다. 도메인별 리소스
 * 참여 권한은 각 메시지 처리기가 추가로 검증하며, 이 인터셉터는 전역 기본 차단을 담당합니다.
 */
@Component
public class StompDestinationAuthorizationInterceptor implements ChannelInterceptor {

    private static final String UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final List<Pattern> ALLOWED_SUBSCRIBE_DESTINATIONS = List.of(
        Pattern.compile("^/sub/contents/" + UUID_PATTERN + "/(?:watch|chat)$"),
        Pattern.compile("^/sub/conversations/" + UUID_PATTERN + "/direct-messages$")
    );

    private static final List<Pattern> ALLOWED_SEND_DESTINATIONS = List.of(
        Pattern.compile("^/pub/contents/" + UUID_PATTERN + "/chat$"),
        Pattern.compile("^/pub/contents/" + UUID_PATTERN + "/watch/heartbeat$"),
        Pattern.compile("^/pub/conversations/" + UUID_PATTERN + "/direct-messages$")
    );

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();

        if (command == StompCommand.SUBSCRIBE) {
            authorize(accessor.getUser(), accessor.getDestination(), ALLOWED_SUBSCRIBE_DESTINATIONS, "구독");
        } else if (command == StompCommand.SEND) {
            authorize(accessor.getUser(), accessor.getDestination(), ALLOWED_SEND_DESTINATIONS, "송신");
        }

        return message;
    }

    private void authorize(
        Principal principal,
        String destination,
        List<Pattern> allowedDestinations,
        String operation
    ) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "STOMP " + operation + "에는 인증이 필요합니다.");
        }

        boolean allowed = destination != null
            && allowedDestinations.stream().anyMatch(pattern -> pattern.matcher(destination).matches());

        if (!allowed) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "허용되지 않은 STOMP " + operation + " 목적지입니다.");
        }
    }
}
