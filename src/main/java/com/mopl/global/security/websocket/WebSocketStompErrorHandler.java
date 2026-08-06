package com.mopl.global.security.websocket;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.stereotype.Component;
import org.springframework.validation.FieldError;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * STOMP 클라이언트 메시지 처리 중 발생한 예외를 표준 STOMP ERROR 프레임으로 변환
 * 직렬화를 통해 프론트가 HTTP/WebSocket 두 채널에서 같은 파싱 로직을 재사용할 수 있게 함
 *
 * 프레임 생성 자체는 StompErrorFrameSender.build()에 위임한다. 스프링 인프라가
 * 이 메서드의 리턴값을 받아 직접 클라이언트로 전송하므로, 여기서는 send()가 아닌
 * build()만 호출해 프레임 생성 로직을 StompMessagingControllerAdvice 등과 공유한다.
 *
 */
@Component
@RequiredArgsConstructor
public class WebSocketStompErrorHandler extends StompSubProtocolErrorHandler {

    private final StompErrorFrameSender errorFrameSender;

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        BusinessException businessException = findBusinessException(ex);

        ErrorCode errorCode;
        String message;
        Map<String, String> details = Map.of();

        if (businessException != null) {
            errorCode = businessException.getErrorCode();
            message = businessException.getMessage();
        } else {
            Map<String, String> validationDetails = findValidationDetails(ex);
            if (validationDetails != null) {
                errorCode = ErrorCode.INVALID_INPUT;
                message = errorCode.getMessage();
                details = validationDetails;
            } else {
                errorCode = ErrorCode.INTERNAL_ERROR;
                message = errorCode.getMessage();
            }
        }

        String exceptionName = (businessException != null ? businessException : ex).getClass()
                .getSimpleName();

        return errorFrameSender.build(clientMessage, exceptionName, errorCode, message, details);
    }

    private BusinessException findBusinessException(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof BusinessException be) {
                return be;
            }
            current = current.getCause();
        }
        return null;
    }

    private Map<String, String> findValidationDetails(Throwable ex) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof MethodArgumentNotValidException manv) {
                Map<String, String> details = new HashMap<>();
                for (FieldError fe : manv.getBindingResult().getFieldErrors()) {
                    details.put(fe.getField(), fe.getDefaultMessage());
                }
                return details;
            }
            if (current instanceof ConstraintViolationException cve) {
                Map<String, String> details = new HashMap<>();
                for (ConstraintViolation<?> v : cve.getConstraintViolations()) {
                    String path = v.getPropertyPath().toString();
                    String field = path.contains(".") ?
                        path.substring(path.lastIndexOf('.') + 1) : path;
                    details.put(field, v.getMessage());
                }
                return details;
            }
            current = current.getCause();
        }
        return null;
    }
}
