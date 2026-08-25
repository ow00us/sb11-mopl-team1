package com.mopl.watchingsession.websocket.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.common.UserSummary;
import com.mopl.watchingsession.dto.ContentChatDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

@ExtendWith(MockitoExtension.class)
class ContentChatBacklogSenderTest {

    @Mock
    private MessageChannel clientOutboundChannel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ContentChatBacklogSender sender;

    private static final String SESSION_ID = "session-1";
    private static final String SUBSCRIPTION_ID = "sub-0";
    private static final String DESTINATION = "/sub/contents/22222222-2222-2222-2222-222222222222/chat";

    private ContentChatDto messageOf(String content) {
        return new ContentChatDto(new UserSummary(UUID.randomUUID(), "우디", null), content);
    }

    @Test
    @DisplayName("백로그 각 메시지를 sessionId·subscriptionId·destination을 채운 MESSAGE 프레임으로 순서대로 전송")
    void send_sendsMessageFramesInOrder() throws Exception {
        // given
        sender = new ContentChatBacklogSender(objectMapper, clientOutboundChannel);
        List<ContentChatDto> backlog = List.of(messageOf("첫번째"), messageOf("두번째"));

        // when
        sender.send(SESSION_ID, SUBSCRIPTION_ID, DESTINATION, backlog);

        // then
        ArgumentCaptor<Message<byte[]>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel, times(2)).send(captor.capture());
        verifyNoMoreInteractions(clientOutboundChannel);

        List<Message<byte[]>> sent = captor.getAllValues();

        StompHeaderAccessor first = StompHeaderAccessor.wrap(sent.get(0));
        assertThat(first.getCommand()).isEqualTo(StompCommand.MESSAGE);
        assertThat(first.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(first.getSubscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(first.getDestination()).isEqualTo(DESTINATION);
        assertThat(first.getMessageId()).isNotBlank();

        ContentChatDto firstBody = objectMapper.readValue(sent.get(0).getPayload(), ContentChatDto.class);
        ContentChatDto secondBody = objectMapper.readValue(sent.get(1).getPayload(), ContentChatDto.class);
        assertThat(firstBody.content()).isEqualTo("첫번째");
        assertThat(secondBody.content()).isEqualTo("두번째");
    }

    @Test
    @DisplayName("메시지마다 서로 다른 message-id를 부여한다")
    void send_assignsDistinctMessageIdPerFrame() {
        // given
        sender = new ContentChatBacklogSender(objectMapper, clientOutboundChannel);
        List<ContentChatDto> backlog = List.of(messageOf("1"), messageOf("2"));

        // when
        sender.send(SESSION_ID, SUBSCRIPTION_ID, DESTINATION, backlog);

        // then
        ArgumentCaptor<Message<byte[]>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel, times(2)).send(captor.capture());

        String firstId = StompHeaderAccessor.wrap(captor.getAllValues().get(0)).getMessageId();
        String secondId = StompHeaderAccessor.wrap(captor.getAllValues().get(1)).getMessageId();
        assertThat(firstId).isNotEqualTo(secondId);
    }

    @Test
    @DisplayName("빈 백로그는 채널에 아무것도 보내지 않는다")
    void send_sendsNothing_whenBacklogIsEmpty() {
        // given
        sender = new ContentChatBacklogSender(objectMapper, clientOutboundChannel);

        // when
        sender.send(SESSION_ID, SUBSCRIPTION_ID, DESTINATION, List.of());

        // then
        verifyNoMoreInteractions(clientOutboundChannel);
    }
}
