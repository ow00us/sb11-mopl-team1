package com.mopl.global.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;

/**
 * StompMessagingControllerAdvice의 순수 단위 테스트
 *
 * 프레임 생성/전송(직렬화, sessionId/receipt 처리, fallback)은 StompErrorFrameSender로
 * 책임이 옮겨갔으므로, 이 클래스는 "예외 타입별로 어떤 errorCode·message·details를
 * 골라 errorFrameSender.send()에 넘기는지"만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
public class StompMessagingControllerAdviceTest {

    @Mock
    private StompErrorFrameSender errorFrameSender;

    @Captor
    private ArgumentCaptor<Map<String, String>> detailsCaptor;

    @InjectMocks
    private StompMessagingControllerAdvice advice;

    private Message<?> sendMessage(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("BusinessException 발생 시 해당 errorCode·message·details로 errorFrameSender.send() 호출")
    void handleBusinessException_delegatesToErrorFrameSender() {
        // given
        Message<?> originalMessage = sendMessage("session-1");
        BusinessException exception = new BusinessException(ErrorCode.CONTENT_NOT_FOUND);

        // when
        advice.handleBusinessException(originalMessage, exception);

        // then
        verify(errorFrameSender).send(
            eq(originalMessage),
            eq("BusinessException"),
            eq(ErrorCode.CONTENT_NOT_FOUND),
            eq(ErrorCode.CONTENT_NOT_FOUND.getMessage()),
            eq(exception.getDetails())
        );
    }

    @Test
    @DisplayName("MethodArgumentNotValidException 발생 시 INVALID_INPUT과 필드별 상세 메시지로 errorFrameSender.send()호출")
    void handleValidationException_delegatesToErrorFrameSenderWithFieldDetails() {
        // given
        Message<?> originalMessage = sendMessage("session-1");

        Method dummyMethod = String.class.getMethods()[0];
        MethodParameter methodParameter = new MethodParameter(dummyMethod, -1);

        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "content", "500자를 초과할 수 없습니다."));
        MethodArgumentNotValidException exception =
            new MethodArgumentNotValidException(originalMessage, methodParameter, bindingResult);

        // when
        advice.handleValidationException(originalMessage, exception);

        // then
        verify(errorFrameSender).send(
            eq(originalMessage),
            eq("MethodArgumentNotValidException"),
            eq(ErrorCode.INVALID_INPUT),
            eq(ErrorCode.INVALID_INPUT.getMessage()),
            detailsCaptor.capture()
        );
        assertThat(detailsCaptor.getValue()).containsEntry("content", "500자를 초과할 수 없습니다.");
    }

    @Test
    @DisplayName("예상하지 못한 Exception 발생 시 INTERNAL_ERROR로 errorFrameSender.send() 호출")
    void handleUnknownException_delegatesToErrorFrameSenderWithInternalError() {
        // given
        Message<?> originalMessage = sendMessage("session-1");
        RuntimeException exception = new RuntimeException("예상 못한 원인");

        // when
        advice.handleUnknownException(originalMessage, exception);

        // then
        verify(errorFrameSender).send(
            eq(originalMessage),
            eq("RuntimeException"),
            eq(ErrorCode.INTERNAL_ERROR),
            eq(ErrorCode.INTERNAL_ERROR.getMessage()),
            eq(Map.of())
        );
    }
}
