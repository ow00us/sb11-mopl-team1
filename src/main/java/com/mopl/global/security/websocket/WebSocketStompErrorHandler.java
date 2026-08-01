package com.mopl.global.security.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.ErrorResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * STOMP 클라이언트 메시지 처리 중 발생한 예외를 표준 STOMP ERROR 프레임으로 변환
 * 직렬화를 통해 프론트가 HTTP/WebSocket 두 채널에서 같은 파싱 로직을 재사용할 수 있게 함
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketStompErrorHandler extends StompSubProtocolErrorHandler {

    private final ObjectMapper objectMapper;

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        BusinessException businessException = findBusinessException(ex);

        ErrorCode errorCode;
        String message;
        if (businessException != null) {
            errorCode = businessException.getErrorCode();
            message = businessException.getMessage();
        } else {
            errorCode = ErrorCode.INTERNAL_ERROR;
            message = errorCode.getMessage();
        }

        String exceptionName = (businessException != null ? businessException : ex).getClass().getSimpleName();

        ErrorResponse body = ErrorResponse.of(
            exceptionName,
            errorCode,
            message,
            Map.of()
        );

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(message);
        accessor.setNativeHeader("content-type", "application/json");
        accessor.setLeaveMutable(true);

        StompHeaderAccessor clientHeaderAccessor = MessageHeaderAccessor.getAccessor(
            clientMessage, StompHeaderAccessor.class);
        if (clientHeaderAccessor != null) {
            String receiptId = clientHeaderAccessor.getReceipt();
            if (receiptId != null) {
                accessor.setReceiptId(receiptId);
            }
        }

        byte[] payload = writeAsBytes(body);
        return MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
    }

    private byte[] writeAsBytes(ErrorResponse body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            log.error("STOMP ERROR 프레임 직렬화 실패 - ErrorResponse: {}", body, e);
            return fallbackPayload();
        }
    }

    private byte[] fallbackPayload() {
        String json = "{\"errorCode\":\"%s\",\"message\":\"%s\"}".formatted(
            ErrorCode.INTERNAL_ERROR.getCode(),
            ErrorCode.INTERNAL_ERROR.getMessage()
        );
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private BusinessException findBusinessException(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null & depth++ < 10) {
            if (current instanceof BusinessException be) {
                return be;
            }
            current = current.getCause();
        }
        return null;
    }
}
