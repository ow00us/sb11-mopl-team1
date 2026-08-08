package com.mopl.global.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
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
 * WebSocketStompErrorHandler의 순수 단위 테스트.
 *
 * 이 핸들러는 프레임을 직접 만들지 않고 StompErrorFrameSender.build()에 위임한 뒤
 * 그 반환값을 그대로 리턴하므로(스프링 인프라가 반환값을 클라이언트로 전송),
 * "예외 타입별로 어떤 인자로 build()를 호출하는지"와 "반환값을 그대로 돌려주는지"를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
public class WebSocketStompErrorHandlerTest {

    @Mock
    private StompErrorFrameSender errorFrameSender;

    @InjectMocks
    private WebSocketStompErrorHandler handler;

    @Captor
    private ArgumentCaptor<Map<String, String>> detailsCaptor;

    private Message<byte[]> clientMessage(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("원인 체인에 BusinessException이 있으면 해당 errorCode·message로 build() 호출하고 결과 반환")
    void handlesBusinessExceptionFromCauseChain() {
        // given
        Message<byte[]> clientMessage = clientMessage("session-1");
        BusinessException businessException = new BusinessException(ErrorCode.CONTENT_NOT_FOUND);
        RuntimeException wrapper = new RuntimeException("wrapper", businessException);

        Message<byte[]> expected = clientMessage("session-1");
        when(errorFrameSender.build(eq(clientMessage), eq("BusinessException"),
            eq(ErrorCode.CONTENT_NOT_FOUND), eq(ErrorCode.CONTENT_NOT_FOUND.getMessage()), eq(Map.of())))
            .thenReturn(expected);

        // when
        Message<byte[]> result = handler.handleClientMessageProcessingError(clientMessage, wrapper);

        // then
        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("원인 체인에 MethodArgumentNotValidException이 있으면 INVALID_INPUT과 필드별 상세로 build() 호출")
    void handlesValidationExceptionFromCauseChain() {
        // given
        Message<byte[]> clientMessage = clientMessage("session-1");

        Method dummyMethod = String.class.getMethods()[0];
        MethodParameter methodParameter = new MethodParameter(dummyMethod, -1);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "content", "500자를 초과할 수 없습니다."));
        MethodArgumentNotValidException validationException =
            new MethodArgumentNotValidException(clientMessage, methodParameter, bindingResult);

        Message<byte[]> expected = clientMessage("session-1");
        when(errorFrameSender.build(eq(clientMessage), eq("MethodArgumentNotValidException"),
            eq(ErrorCode.INVALID_INPUT), eq(ErrorCode.INVALID_INPUT.getMessage()), any()))
            .thenReturn(expected);

        // when
        Message<byte[]> result = handler.handleClientMessageProcessingError(clientMessage, validationException);

        // then
        assertThat(result).isSameAs(expected);
        verify(errorFrameSender).build(eq(clientMessage), eq("MethodArgumentNotValidException"),
            eq(ErrorCode.INVALID_INPUT), eq(ErrorCode.INVALID_INPUT.getMessage()), detailsCaptor.capture());
        assertThat(detailsCaptor.getValue()).containsEntry("content", "500자를 초과할 수 없습니다.");
    }

    @Test
    @DisplayName("원인 체인에 ConstraintViolationException이 있으면 필드별 상세를 추출해 build()를 호출")
    void handlesConstraintViolationExceptionFromCauseChain() {
        // given
        Message<byte[]> clientMessage = clientMessage("session-1");

        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = org.mockito.Mockito.mock(ConstraintViolation.class);
        jakarta.validation.Path path = org.mockito.Mockito.mock(jakarta.validation.Path.class);
        when(path.toString()).thenReturn("method.arg0");
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("잘못된 값입니다.");

        ConstraintViolationException cve = new ConstraintViolationException(Set.of(violation));

        Message<byte[]> expected = clientMessage("session-1");
        when(errorFrameSender.build(eq(clientMessage), eq("ConstraintViolationException"),
            eq(ErrorCode.INVALID_INPUT), eq(ErrorCode.INVALID_INPUT.getMessage()), any()))
            .thenReturn(expected);

        // when
        Message<byte[]> result = handler.handleClientMessageProcessingError(clientMessage, cve);

        // then
        assertThat(result).isSameAs(expected);
        verify(errorFrameSender).build(eq(clientMessage), eq("ConstraintViolationException"),
            eq(ErrorCode.INVALID_INPUT), eq(ErrorCode.INVALID_INPUT.getMessage()), detailsCaptor.capture());
        assertThat(detailsCaptor.getValue()).containsEntry("arg0", "잘못된 값입니다.");
    }

    @Test
    @DisplayName("BusinessException도, 검증 예외도 아니면 INTERNAL_ERROR로 build()를 호출")
    void handlesUnknownExceptionAsInternalError() {
        // given
        Message<byte[]> clientMessage = clientMessage("session-1");
        RuntimeException unknown = new RuntimeException("알 수 없는 원인");

        Message<byte[]> expected = clientMessage("session-1");
        when(errorFrameSender.build(eq(clientMessage), eq("RuntimeException"),
            eq(ErrorCode.INTERNAL_ERROR), eq(ErrorCode.INTERNAL_ERROR.getMessage()), eq(Map.of())))
            .thenReturn(expected);

        // when
        Message<byte[]> result = handler.handleClientMessageProcessingError(clientMessage, unknown);

        // then
        assertThat(result).isSameAs(expected);
    }

}
