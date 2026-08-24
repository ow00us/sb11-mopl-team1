package com.mopl.watchingsession.websocket.listener;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.common.UserSummary;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.presence.ContentChatBuffer;
import com.mopl.watchingsession.websocket.broadcast.ContentChatBacklogSender;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@ExtendWith(MockitoExtension.class)
class ContentChatBacklogListenerTest {

    @Mock
    private ContentChatBuffer contentChatBuffer;

    @Mock
    private ContentChatBacklogSender contentChatBacklogSender;

    @InjectMocks
    private ContentChatBacklogListener listener;

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SESSION_ID = "session-0";
    private static final String SUBSCRIPTION_ID = "sub-0";

    private SessionSubscribeEvent subscribeEvent(String destination, String sessionId, String subscriptionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);

        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        if (sessionId != null) {
            accessor.setSessionId(sessionId);
        }

        accessor.setSessionAttributes(new HashMap<>());
        accessor.setLeaveMutable(true);
        accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(WATCHER_ID.toString(), null, List.of()));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(this, message);
    }

    private ContentChatDto messageOf(String content) {
        return new ContentChatDto(new UserSummary(WATCHER_ID, "우디", null), content);
    }

    @Test
    @DisplayName("채팅 토픽 구독 시 버퍼 메시지가 있으면 해당 세션에 전송한다")
    void onSubscribe_sendsBacklog_whenBufferHasMessages() {
        // given
        String destination = "/sub/contents/" + CONTENT_ID + "/chat";
        List<ContentChatDto> backlog = List.of(messageOf("1"), messageOf("2"));
        when(contentChatBuffer.recent(CONTENT_ID)).thenReturn(backlog);

        SessionSubscribeEvent event = subscribeEvent(destination, SESSION_ID, SUBSCRIPTION_ID);

        // when
        listener.onSubscribe(event);

        // then
        verify(contentChatBacklogSender).send(SESSION_ID, SUBSCRIPTION_ID, destination, backlog);
    }

    @Test
    @DisplayName("버퍼가 비어 있으면 sender를 호출하지 않는다")
    void onSubscribe_doesNotCallSender_whenBufferIsEmpty() {
        // given
        String destination = "/sub/contents/" + CONTENT_ID + "/chat";
        when(contentChatBuffer.recent(CONTENT_ID)).thenReturn(List.of());

        SessionSubscribeEvent event = subscribeEvent(destination, SESSION_ID, SUBSCRIPTION_ID);

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(contentChatBacklogSender);
    }

    @Test
    @DisplayName("watch 토픽 구독에는 관여하지 않는다")
    void onSubscribe_ignoresWatchDestination() {
        // given
        String destination = "/sub/contents/" + CONTENT_ID + "/watch";
        SessionSubscribeEvent event = subscribeEvent(destination, SESSION_ID, SUBSCRIPTION_ID);

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(contentChatBuffer);
        verifyNoInteractions(contentChatBacklogSender);
    }

    @Test
    @DisplayName("DM 구독 등 다른 목적지에는 관여하지 않는다")
    void onSubscribe_ignoresOtherDestinations() {
        // given
        String destination = "/sub/conversations/" + UUID.randomUUID() + "/direct-messages";
        SessionSubscribeEvent event = subscribeEvent(destination, SESSION_ID, SUBSCRIPTION_ID);

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(contentChatBuffer);
        verifyNoInteractions(contentChatBacklogSender);
    }

    @Test
    @DisplayName("contentId가 UUID 형식이 아니면 무시한다")
    void onSubscribe_ignoresInvalidContentIdFormat() {
        // given
        String destination = "/sub/contents/not-a-uuid/chat";
        SessionSubscribeEvent event = subscribeEvent(destination, SESSION_ID, SUBSCRIPTION_ID);

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(contentChatBuffer);
        verifyNoInteractions(contentChatBacklogSender);
    }

    @Test
    @DisplayName("sessionId가 없으면 버퍼를 조회하지 않고 조용히 넘어간다")
    void onSubscribe_skipsSilently_whenSessionIdMissing() {
        // given
        String destination = "/sub/contents/" + CONTENT_ID + "/chat";
        SessionSubscribeEvent event = subscribeEvent(destination, null, SUBSCRIPTION_ID);

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(contentChatBuffer);
        verifyNoInteractions(contentChatBacklogSender);
    }

    @Test
    @DisplayName("subscriptionId가 없으면 버퍼를 조회하지 않고 조용히 넘어간다")
    void onSubscribe_skipsSilently_whenSubscriptionIdMissing() {
        // given
        String destination = "/sub/contents/" + CONTENT_ID + "/chat";
        SessionSubscribeEvent event = subscribeEvent(destination, SESSION_ID, null);

        // when
        listener.onSubscribe(event);

        // then
        verifyNoInteractions(contentChatBuffer);
        verifyNoInteractions(contentChatBacklogSender);
    }
}
