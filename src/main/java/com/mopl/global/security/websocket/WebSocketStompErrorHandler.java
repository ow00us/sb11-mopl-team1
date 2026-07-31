package com.mopl.global.security.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.ErrorResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * STOMP 클라이언트 메시지 처리 중 발생한 예외를 표준 STOMP ERROR 프레임으로 변환
 * 직렬화를 통해 프론트가 HTTP/WebSocket 두 채널에서 같은 파싱 로직을 재사용할 수 있게 함
 */
@Component
@RequiredArgsConstructor
public class WebSocketStompErrorHandler extends StompSubProtocolErrorHandler {

    private final ObjectMapper objectMapper;

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        ErrorCode errorCode = (cause instanceof BusinessException be)
            ? be.getErrorCode()
            : ErrorCode.INTERNAL_ERROR;

        ErrorResponse body = ErrorResponse.of(
            cause.getClass().getSimpleName(),
            errorCode,
            errorCode.getMessage(),
            Map.of()
        );

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(errorCode.getMessage());
        accessor.setNativeHeader("content-type", "application/json");
        accessor.setLeaveMutable(true);

        byte[] payload = writeAsBytes(body);
        return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
    }

    private byte[] writeAsBytes(ErrorResponse body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            return "{\"errorCode\":\"COMMON_500_1\",\"message\":\"서버 오류가 발생했습니다.\"}"
                .getBytes(StandardCharsets.UTF_8);
        }
    }
}
