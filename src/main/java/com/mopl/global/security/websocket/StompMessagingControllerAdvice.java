package com.mopl.global.security.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.ErrorResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * STOMP @MessageMapping 메서드(파라미터 바인딩 포함) 안에서 발생하는 예외를 전역 처리한다.
 *
 * WebSocketStompErrorHandler(StompSubProtocolErrorHandler)는 CONNECT 인증 등
 * "채널 레벨"에서 발생하는 예외만 잡을 수 있고, @MessageMapping 메서드 내부나
 * @Payload @Valid 파라미터 바인딩 실패는 AbstractMethodMessageHandler가 처리하는
 * 별개의 경로라 그 핸들러로는 잡히지 않는다(로그에 "Unhandled exception from
 * message handler method"로만 남고 클라이언트에는 아무 응답도 가지 않음).
 *
 * SimpAnnotationMethodMessageHandler는 @ControllerAdvice가
 * 붙은 빈을 자동으로 스캔해 @MessageExceptionHandler를 전역 적용한다. REST 전용
 * @RestControllerAdvice(GlobalExceptionHandler)와는 별개의 빈이며, 이 클래스는
 * ContentChatController뿐 아니라 이후 추가되는 모든 @MessageMapping 컨트롤러에 전역 적용된다.
 *
 * WebSocketStompErrorHandler와 클라이언트 응답 계약을 일치시키기 위해,
 * @SendToUser(MESSAGE 프레임 + 별도 구독 필요) 대신 동일한 STOMP ERROR 커맨드 프레임을
 * 직접 만들어 clientOutboundChannel로 전송한다. 이렇게 하면 클라이언트는 CONNECT 인증 실패든
 * 채팅 전송 검증 실패든 항상 같은 방식(ERROR 프레임, 추가 구독 불필요)으로 에러를 받는다.
 */
@Slf4j
@ControllerAdvice
public class StompMessagingControllerAdvice {

    private final ObjectMapper objectMapper;
    private final MessageChannel clientOutboundChannel;

    public StompMessagingControllerAdvice(
        ObjectMapper objectMapper,
        @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel
    ) {
        this.objectMapper = objectMapper;
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @MessageExceptionHandler(BusinessException.class)
    public void handleBusinessException(Message<?> originalMessage, BusinessException e) {
        log.warn("STOMP BusinessException: {} - {}", e.getErrorCode().getCode(), e.getMessage());
        sendErrorFrame(originalMessage, e.getClass().getSimpleName(), e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    public void handleValidationException(Message<?> originalMessage, MethodArgumentNotValidException e) {
        Map<String, String> details = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("STOMP MethodArgumentNotValidException: {}", details);
        ErrorCode code = ErrorCode.INVALID_INPUT;
        sendErrorFrame(originalMessage, e.getClass().getSimpleName(), code, code.getMessage(), details);
    }

    @MessageExceptionHandler(Exception.class)
    public void handleUnknownException(Message<?> originalMessage, Exception e) {
        log.error("STOMP 메시지 처리 중 예상하지 못한 예외 발생", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        sendErrorFrame(originalMessage, e.getClass().getSimpleName(), code, code.getMessage(), Map.of());
    }

    /**
     * WebSocketStompErrorHandler.handleClientMessageProcessingError와 동일한 형태로
     * STOMP ERROR 커맨드 프레임을 만들어 클라이언트에게 직접 전송한다.
     */
    private void sendErrorFrame(Message<?> originalMessage, String exceptionName, ErrorCode errorCode,
        String message, Map<String, String> details) {

        ErrorResponse body = ErrorResponse.of(exceptionName, errorCode, message, details);

        StompHeaderAccessor originalAccessor = StompHeaderAccessor.wrap(originalMessage);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(message);
        accessor.setSessionId(originalAccessor.getSessionId());
        accessor.setHeader(SimpMessageHeaderAccessor.MESSAGE_TYPE_HEADER, SimpMessageType.MESSAGE);

        accessor.setLeaveMutable(true);

        String receiptId = originalAccessor.getReceipt();
        if (receiptId != null) {
            accessor.setReceiptId(receiptId);
        }

        byte[] payload = writeAsBytes(body);
        Message<byte[]> errorMessage = MessageBuilder.createMessage(payload, accessor.getMessageHeaders());
        clientOutboundChannel.send(errorMessage);
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
