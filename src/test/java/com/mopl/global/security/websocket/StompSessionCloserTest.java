package com.mopl.global.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/**
 * StompSessionCloser 단위 테스트.
 *
 * clientOutboundChannel 로 SimpMessageType.DISCONNECT_ACK 메시지가 원본 메시지의
 * sessionId 를 이어받아 전송되는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class StompSessionCloserTest {

    @Mock
    private MessageChannel clientOutboundChannel;

    @Captor
    private ArgumentCaptor<Message<byte[]>> messageCaptor;

    private StompSessionCloser sessionCloser;

    @BeforeEach
    void setUp() {
        sessionCloser = new StompSessionCloser(clientOutboundChannel);
    }

    private Message<?> subscribeMessage(String sessionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }
        accessor.setDestination("/sub/contents/xxx/watch");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("원본 메시지의 sessionId 로 DISCONNECT_ACK 메시지를 outbound 채널에 전송한다")
    void close_sendsDisconnectAckWithSessionId() {
        // given
        String sessionId = "session-abc-123";
        Message<?> original = subscribeMessage(sessionId);

        // when
        sessionCloser.close(original);

        // then
        verify(clientOutboundChannel).send(messageCaptor.capture());
        Message<byte[]> sent = messageCaptor.getValue();

        SimpMessageHeaderAccessor sentAccessor = SimpMessageHeaderAccessor
            .getAccessor(sent, SimpMessageHeaderAccessor.class);
        assertThat(sentAccessor).isNotNull();
        assertThat(sentAccessor.getMessageType()).isEqualTo(SimpMessageType.DISCONNECT_ACK);
        assertThat(sentAccessor.getSessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("원본 메시지에 sessionId 가 없으면 전송을 건너뛴다")
    void close_skipsWhenSessionIdMissing() {
        // given
        Message<?> original = subscribeMessage(null);

        // when
        sessionCloser.close(original);

        // then
        verifyNoInteractions(clientOutboundChannel);
    }

    @Test
    @DisplayName("서로 다른 sessionId 를 가진 여러 원본 메시지를 각각 독립적으로 처리한다")
    void close_handlesMultipleSessionsIndependently() {
        // given
        Message<?> first = subscribeMessage("session-1");
        Message<?> second = subscribeMessage("session-2");

        // when
        sessionCloser.close(first);
        sessionCloser.close(second);

        // then: 두 번 전송되고 각각의 sessionId 가 유지됨
        verify(clientOutboundChannel, org.mockito.Mockito.times(2)).send(any());
    }
}
