package com.mopl.global.security.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.ErrorResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * STOMP ERROR 커맨드 프레임을 만들어 clientOutboundChannel로 직접 전송하는 공통 유틸.
 *
 * 기존에 StompMessagingControllerAdvice(@MessageMapping 전용 @MessageExceptionHandler 경로)와
 * WebSocketStompErrorHandler(StompSubProtocolErrorHandler, 채널 레벨 전용)가 각자
 * 거의 동일한 ERROR 프레임 생성 로직을 중복 구현하고 있었다
 *
 * 세 경로(메시지 매핑 예외, 채널 레벨 예외, 이벤트 리스너 예외) 모두 이 클래스를
 * 통해 같은 형태의 ERROR 프레임을 만들도록 일원화한다.
 */
@Slf4j
@Component
public class StompErrorFrameSender {

    private final ObjectMapper objectMapper;
    private final MessageChannel clientOutboundChannel;

    public StompErrorFrameSender(
        ObjectMapper objectMapper,
        @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel
    ) {
        this.objectMapper = objectMapper;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    // 원본 STOMP 메시지의 sessionId/receipt를 이어받아 ERROR 프레임을 만들고, clientOutboundChannel로 직접 전송까지 수행한다.
    public void send(Message<?> originalMessage, String exceptionName, ErrorCode errorCode, String message, Map<String, String> details) {
        Message<byte[]> errorMessage = build(originalMessage, exceptionName, errorCode, message, details);
        clientOutboundChannel.send(errorMessage);
    }

    /**
     * 원본 STOMP 메시지의 sessionId/receipt를 그대로 이어받아 ERROR 프레임을 만들어 반환만 한다
     * 전송 자체를 스프링 인프라가 대신 처리하는 호출부에서 사용
     *
     * @param originalMessage 에러의 원인이 된 원본 STOMP 메시지 (SUBSCRIBE, SEND 등)
     * @param exceptionName   클라이언트에 노출할 예외 클래스명
     * @param errorCode       도메인 에러 코드
     * @param message         사용자에게 보여줄 메시지
     * @param details         필드별 상세 (검증 에러 등에 사용, 없으면 빈 맵)
     */
    public Message<byte[]> build(Message<?> originalMessage, String exceptionName, ErrorCode errorCode, String message, Map<String, String> details) {
        ErrorResponse body = ErrorResponse.of(exceptionName, errorCode, message, details);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(message);
        accessor.setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.MESSAGE);
        accessor.setLeaveMutable(true);

        if (originalMessage != null) {
            StompHeaderAccessor originalAccessor = StompHeaderAccessor.wrap(originalMessage);
            accessor.setSessionId(originalAccessor.getSessionId());

            String receiptId = originalAccessor.getReceipt();
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
            String json = """
                {"exceptionName":"%s","errorCode":"%s","message":"%s","details":{}}"""
                .formatted(
                    "SerializationFailure",
                    ErrorCode.INTERNAL_ERROR.getCode(),
                    ErrorCode.INTERNAL_ERROR.getMessage()
                );
            return json.getBytes(StandardCharsets.UTF_8);
        }
    }
}
