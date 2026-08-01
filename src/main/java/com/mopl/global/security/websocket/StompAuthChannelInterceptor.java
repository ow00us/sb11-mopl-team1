package com.mopl.global.security.websocket;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT 프레임의 Authorization 헤더(Bearer JWT)를 검증하고
 * 인증된 사용자를 Principal로 바인딩합니다
 * REST API 인증과 동일한 토큰 검증 로직(JwtProvider)를 사용하며
 * 인증 실패 시에는 예외를 던져 연결을 거부합니다
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final JwtProvider jwtProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (!StompCommand.CONNECT.equals(StompHeaderAccessor.wrap(message).getCommand())) {
            return message;
        }

        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "WebSocket 인증 처리에 실패했습니다.");
        }

        accessor.setUser(authenticate(accessor));
        return message;
    }

    private Authentication authenticate(StompHeaderAccessor accessor) {
        String authorizationHeader = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "WebSocket 연결에는 Authorization 헤더가 필요합니다.");
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length());

        if (!jwtProvider.validate(token)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 액세스 토큰입니다.");
        }

        return jwtProvider.getAuthentication(token);
    }
}
