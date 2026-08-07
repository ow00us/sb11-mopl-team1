package com.mopl.global.security.websocket;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * STOMP @MessageMapping 메서드(파라미터 바인딩 포함) 안에서 발생하는 예외를 전역 처리한다.
 *
 * WebSocketStompErrorHandler(StompSubProtocolErrorHandler)는 CONNECT 인증 등
 * "채널 레벨"에서 발생하는 예외만 잡을 수 있고, @MessageMapping 메서드 내부나
 * @ Payload @Valid 파라미터 바인딩 실패는 AbstractMethodMessageHandler가 처리하는
 * 별개의 경로라 그 핸들러로는 잡히지 않는다(로그에 "Unhandled exception from
 * message handler method"로만 남고 클라이언트에는 아무 응답도 가지 않음).
 *
 * SimpAnnotationMethodMessageHandler는 @ControllerAdvice가
 * 붙은 빈을 자동으로 스캔해 @MessageExceptionHandler를 전역 적용한다. REST 전용
 * @ RestControllerAdvice(GlobalExceptionHandler)와는 별개의 빈이며, 이 클래스는
 * ContentChatController뿐 아니라 이후 추가되는 모든 @MessageMapping 컨트롤러에 전역 적용된다.
 *
 * 실제 ERROR 프레임 생성/전송은 StompErrorFrameSender에 위임한다. 이렇게 하면
 * WebSocketStompErrorHandler(채널 레벨), 이후 추가되는 @EventListener 기반 리스너 등
 * 다른 경로에서 발생한 예외도 동일한 프레임 포맷·전송 방식으로 클라이언트에 전달할 수 있다.
 */
@Slf4j
@ControllerAdvice
public class StompMessagingControllerAdvice {

    private final StompErrorFrameSender errorFrameSender;

    public StompMessagingControllerAdvice(@Lazy StompErrorFrameSender errorFrameSender) {
        this.errorFrameSender = errorFrameSender;
    }

    @MessageExceptionHandler(BusinessException.class)
    public void handleBusinessException(Message<?> originalMessage, BusinessException e) {
        log.warn("STOMP BusinessException: {} - {}", e.getErrorCode().getCode(), e.getMessage());
        errorFrameSender.send(originalMessage, e.getClass().getSimpleName(), e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    public void handleValidationException(Message<?> originalMessage, MethodArgumentNotValidException e) {
        Map<String, String> details = new HashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            details.put(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("STOMP MethodArgumentNotValidException: {}", details);
        ErrorCode code = ErrorCode.INVALID_INPUT;
        errorFrameSender.send(originalMessage, e.getClass().getSimpleName(), code, code.getMessage(), details);
    }

    @MessageExceptionHandler(Exception.class)
    public void handleUnknownException(Message<?> originalMessage, Exception e) {
        log.error("STOMP 메시지 처리 중 예상하지 못한 예외 발생", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        errorFrameSender.send(originalMessage, e.getClass().getSimpleName(), code, code.getMessage(), Map.of());
    }
}
