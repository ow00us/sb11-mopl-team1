package com.mopl.global.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.ErrorResponse;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;

/**
 * StompMessagingControllerAdvice의 순수 단위 테스트
 *
 * WebSocketStompErrorHandlerTest와 동일한 패턴(Mockito 단독, WebSocket 서버 기동 없음)을
 * 재사용한다. clientOutboundChannel은 Mock으로 대체해 send()에 실제로 어떤 Message가
 * 전달되는지 ArgumentCaptor로 캡처해서 검증한다.
 *
 * 핸들러가 어떤 BusinessException을 받았을 때 어떤 필드 값을 담은
 * Message를 clientOutboundChannel에 넘기는지만 확인한다.
 */
@ExtendWith(MockitoExtension.class)
public class StompMessagingControllerAdviceTest {
    @Mock
    private MessageChannel clientOutboundChannel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StompMessagingControllerAdvice advice;

    @BeforeEach
    void setUp() {
        // @Qualifier가 붙은 생성자라 @InjectMocks 대신 직접 생성해 명시적으로 연결한다.
        advice = new StompMessagingControllerAdvice(objectMapper, clientOutboundChannel);
    }

    private Message<?> sendMessage(String sessionId, String receiptId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setSessionId(sessionId);
        if (receiptId != null) {
            accessor.setReceipt(receiptId);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("BusinessException 발생 시 errorCode·message·details를 담은 ERROR 프레임을 clientOutboundChannel로 전송")
    void handleBusinessException_sendsErrorFrameWithBusinessErrorCode() throws Exception {
        // given
        String sessionId = "session-1";
        Message<?> originalMessage = sendMessage(sessionId, null);
        BusinessException exception = new BusinessException(ErrorCode.CONTENT_NOT_FOUND);

        // when
        advice.handleBusinessException(originalMessage, exception);

        // then
        ArgumentCaptor<Message<byte[]>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(captor.capture());

        Message<byte[]> sent = captor.getValue();
        StompHeaderAccessor sentAccessor = StompHeaderAccessor.wrap(sent);
        assertThat(sentAccessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(sentAccessor.getSessionId()).isEqualTo(sessionId);

        ErrorResponse body = objectMapper.readValue(sent.getPayload(), ErrorResponse.class);
        assertThat(body.errorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND.getCode());
        assertThat(body.message()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND.getMessage());
        assertThat(body.exceptionName()).isEqualTo("BusinessException");
    }

    @Test
    @DisplayName("receipt 헤더가 있는 요청에서 예외가 발생하면 응답 프레임에 receipt-id로 그대로 이어받음")
    void handleBusinessException_propagatesReceiptId() throws Exception {
        // given
        String receiptId = "receipt-42";
        Message<?> originalMessage = sendMessage("session-1", receiptId);
        BusinessException exception = new BusinessException(ErrorCode.CONTENT_NOT_FOUND);

        // when
        advice.handleBusinessException(originalMessage, exception);

        // then
        ArgumentCaptor<Message<byte[]>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(captor.capture());

        StompHeaderAccessor sentAccessor = StompHeaderAccessor.wrap(captor.getValue());
        assertThat(sentAccessor.getReceiptId()).isEqualTo(receiptId);
    }


    @Test
    @DisplayName("MethodArgumentNotValidException 발생 시 INVALID_INPUT과 필드별 상세 메시지를 담은 프레임을 전송")
    void handleValidationException_sendsErrorFrameWithFieldDetails() throws Exception {
        // given
        Message<?> originalMessage = sendMessage("session-1", null);

        Method dummyMethod = String.class.getMethods()[0];
        MethodParameter methodParameter = new MethodParameter(dummyMethod, -1);

        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "content", "500자를 초과할 수 없습니다."));
        MethodArgumentNotValidException exception =
            new MethodArgumentNotValidException(originalMessage, methodParameter, bindingResult);

        // when
        advice.handleValidationException(originalMessage, exception);

        // then
        ArgumentCaptor<Message<byte[]>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(captor.capture());

        ErrorResponse body = objectMapper.readValue(captor.getValue().getPayload(), ErrorResponse.class);
        assertThat(body.errorCode()).isEqualTo(ErrorCode.INVALID_INPUT.getCode());
        assertThat(body.details()).containsEntry("content", "500자를 초과할 수 없습니다.");
    }

    @Test
    @DisplayName("예상하지 못한 Exception 발생 시 INTERNAL_ERROR 프레임을 전송")
    void handleUnknownException_sendsInternalErrorFrame() throws Exception {
        // given
        Message<?> originalMessage = sendMessage("session-1", null);
        RuntimeException exception = new RuntimeException("예상 못한 원인");

        // when
        advice.handleUnknownException(originalMessage, exception);

        // then
        ArgumentCaptor<Message<byte[]>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(captor.capture());

        ErrorResponse body = objectMapper.readValue(captor.getValue().getPayload(), ErrorResponse.class);
        assertThat(body.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());
        assertThat(body.exceptionName()).isEqualTo("RuntimeException");
    }

    @Test
    @DisplayName("ObjectMapper 직렬화 실패 시 fallback JSON을 전송")
    void sendErrorFrame_fallsBackToRawJson_whenSerializationFails() throws Exception {
        // given: 순환 참조 등으로 직렬화가 실패하는 상황을 흉내내기 위해 실패하는 ObjectMapper Mock 사용
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(failingMapper.writeValueAsBytes(any()))
            .thenThrow(new JsonGenerationException("직렬화 실패", (JsonGenerator) null));
        StompMessagingControllerAdvice failingAdvice =
            new StompMessagingControllerAdvice(failingMapper, clientOutboundChannel);

        Message<?> originalMessage = sendMessage("session-1", null);
        BusinessException exception = new BusinessException(ErrorCode.CONTENT_NOT_FOUND);

        // when
        failingAdvice.handleBusinessException(originalMessage, exception);

        // then: fallback 문자열이 그대로 전송되는지 확인 (INTERNAL_ERROR 코드 포함)
        ArgumentCaptor<Message<byte[]>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(captor.capture());

        String payload = new String(captor.getValue().getPayload());
        assertThat(payload).contains(ErrorCode.INTERNAL_ERROR.getCode());
        assertThat(payload).contains("SerializationFailure");
    }
}
