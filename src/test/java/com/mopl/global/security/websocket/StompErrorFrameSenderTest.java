package com.mopl.global.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.ErrorResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * StompErrorFrameSender의 순수 단위 테스트.
 *
 * ERROR 프레임 생성(build)과 전송(send) 두 경로 모두 검증한다.
 * clientOutboundChannel은 Mock으로 대체해 send() 호출 여부와 실제 전달된
 * Message 내용을 ArgumentCaptor로 캡처해서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
public class StompErrorFrameSenderTest {

    @Mock
    private MessageChannel clientOutboundChannel;

    @Captor
    private ArgumentCaptor<Message<byte[]>> messageCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private StompErrorFrameSender sender;

    @BeforeEach
    void setUp() {
        sender = new StompErrorFrameSender(objectMapper, clientOutboundChannel);
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
    @DisplayName("send()는 errorCode·message·details를 담은 ERROR 프레임을 clientOutboundChannel로 전송")
    void send_sendsErrorFrameToClientOutboundChannel() throws Exception {
        // given
        String sessionId = "session-1";
        Message<?> originalMessage = sendMessage(sessionId, null);

        // when
        sender.send(originalMessage, "BusinessException", ErrorCode.CONTENT_NOT_FOUND,
            ErrorCode.CONTENT_NOT_FOUND.getMessage(), Map.of());

        // then
        verify(clientOutboundChannel).send(messageCaptor.capture());

        Message<byte[]> sent = messageCaptor.getValue();
        StompHeaderAccessor sentAccessor = StompHeaderAccessor.wrap(sent);
        assertThat(sentAccessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(sentAccessor.getSessionId()).isEqualTo(sessionId);

        ErrorResponse body = objectMapper.readValue(sent.getPayload(), ErrorResponse.class);
        assertThat(body.errorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND.getCode());
        assertThat(body.message()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND.getMessage());
        assertThat(body.exceptionName()).isEqualTo("BusinessException");
        assertThat(sentAccessor.getMessageType()).isEqualTo(SimpMessageType.MESSAGE);
    }

    @Test
    @DisplayName("receipt 헤더가 있는 원본 메시지라면 응답 프레임에 receipt-id를 그대로 이어받음")
    void send_propagatesReceiptId() {
        // given
        String receiptId = "receipt-42";
        Message<?> originalMessage = sendMessage("session-1", receiptId);

        // when
        sender.send(originalMessage, "BusinessException", ErrorCode.CONTENT_NOT_FOUND,
            ErrorCode.CONTENT_NOT_FOUND.getMessage(), Map.of());

        // then
        verify(clientOutboundChannel).send(messageCaptor.capture());
        StompHeaderAccessor sentAccessor = StompHeaderAccessor.wrap(messageCaptor.getValue());
        assertThat(sentAccessor.getReceiptId()).isEqualTo(receiptId);
    }

    @Test
    @DisplayName("build()는 채널로 전송하지 않고 ERROR 프레임 Message만 반환")
    void build_returnsFrameWithoutSending() throws Exception {
        // given
        Message<?> originalMessage = sendMessage("session-1", null);

        // when
        Message<byte[]> built = sender.build(originalMessage, "BusinessException",
            ErrorCode.CONTENT_NOT_FOUND, ErrorCode.CONTENT_NOT_FOUND.getMessage(), Map.of());

        // then
        verifyNoInteractions(clientOutboundChannel);

        StompHeaderAccessor builtAccessor = StompHeaderAccessor.wrap(built);
        assertThat(builtAccessor.getCommand()).isEqualTo(StompCommand.ERROR);

        ErrorResponse body = objectMapper.readValue(built.getPayload(), ErrorResponse.class);
        assertThat(body.errorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("ObjectMapper 직렬화 실패 시 fallback JSON을 담은 프레임을 전송")
    void send_fallsBackToRawJson_whenSerializationFails() throws Exception {
        // given: 순환 참조 등으로 직렬화가 실패하는 상황을 흉내내기 위해 실패하는 ObjectMapper Mock 사용
        ObjectMapper failingMapper = Mockito.mock(ObjectMapper.class);
        when(failingMapper.writeValueAsBytes(any()))
            .thenThrow(new JsonGenerationException("직렬화 실패", (JsonGenerator) null));
        StompErrorFrameSender failingSender = new StompErrorFrameSender(failingMapper, clientOutboundChannel);

        Message<?> originalMessage = sendMessage("session-1", null);

        // when
        failingSender.send(originalMessage, "BusinessException", ErrorCode.CONTENT_NOT_FOUND,
            ErrorCode.CONTENT_NOT_FOUND.getMessage(), Map.of());

        // then: fallback 문자열이 그대로 전송되는지 확인
        verify(clientOutboundChannel).send(messageCaptor.capture());

        String payload = new String(messageCaptor.getValue().getPayload());
        assertThat(payload).contains(ErrorCode.INTERNAL_ERROR.getCode());
        assertThat(payload).contains("SerializationFailure");
    }

    @Test
    @DisplayName("originalMessage가 null이면 sessionId/receipt 없이 ERROR 프레임을 생성하고 전송한다 (NPE 없이)")
    void send_handlesNullOriginalMessage_withoutSessionIdOrReceipt() throws Exception {
        // when
        sender.send(null, "BusinessException", ErrorCode.CONTENT_NOT_FOUND,
            ErrorCode.CONTENT_NOT_FOUND.getMessage(), Map.of());

        // then
        verify(clientOutboundChannel).send(messageCaptor.capture());

        Message<byte[]> sent = messageCaptor.getValue();
        StompHeaderAccessor sentAccessor = StompHeaderAccessor.wrap(sent);
        assertThat(sentAccessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(sentAccessor.getSessionId()).isNull();
        assertThat(sentAccessor.getReceiptId()).isNull();

        ErrorResponse body = objectMapper.readValue(sent.getPayload(), ErrorResponse.class);
        assertThat(body.errorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("build()도 originalMessage가 null이면 NPE 없이 프레임을 반환")
    void build_handlesNullOriginalMessage_withoutThrowing() {
        // when & then: 예외 없이 정상 반환되어야 함
        Message<byte[]> built = sender.build(null, "BusinessException",
            ErrorCode.CONTENT_NOT_FOUND, ErrorCode.CONTENT_NOT_FOUND.getMessage(), Map.of());

        StompHeaderAccessor builtAccessor = StompHeaderAccessor.wrap(built);
        assertThat(builtAccessor.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(builtAccessor.getSessionId()).isNull();

        Mockito.verifyNoInteractions(clientOutboundChannel);
    }
}
