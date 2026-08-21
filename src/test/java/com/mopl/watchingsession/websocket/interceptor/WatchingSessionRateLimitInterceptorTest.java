package com.mopl.watchingsession.websocket.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.watchingsession.config.WatchingSessionProperties;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

public class WatchingSessionRateLimitInterceptorTest {

    private static final String CONTENT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String HEARTBEAT_DEST = "/pub/contents/" + CONTENT_ID + "/watch/heartbeat";
    private static final String CHAT_SEND_DEST = "/pub/contents/" + CONTENT_ID + "/chat";
    private static final String WATCH_SUB_DEST = "/sub/contents/" + CONTENT_ID + "/watch";
    private static final String CHAT_SUB_DEST = "/sub/contents/" + CONTENT_ID + "/chat";
    private static final String DM_SEND_DEST = "/pub/conversations/" + CONTENT_ID + "/direct-messages";

    private WatchingSessionProperties properties;
    private WatchingSessionRateLimitMetrics metrics;
    private WatchingSessionRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        properties = mock(WatchingSessionProperties.class);
        metrics = mock(WatchingSessionRateLimitMetrics.class);
        interceptor = new WatchingSessionRateLimitInterceptor(properties, metrics);

        when(properties.getHeartbeatInterval()).thenReturn(Duration.ofSeconds(20)); // 하한 10s
        when(properties.getChatSendMinInterval()).thenReturn(Duration.ofSeconds(10));
        when(properties.getWatchSubscribeMinInterval()).thenReturn(Duration.ofSeconds(10));
        when(properties.getChatSubscriptionLimit()).thenReturn(2);
    }

    private Message<?> message(StompCommand command, String destination, String subscriptionId,
        Map<String, Object> sessionAttributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        if (subscriptionId != null) {
            accessor.setSubscriptionId(subscriptionId);
        }
        accessor.setSessionAttributes(sessionAttributes);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // heartbeat SEND

    @Test
    @DisplayName("같은 연결에서 첫 heartbeat SEND는 통과한다")
    void heartbeatSend_firstFrame_passes() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        Message<?> msg = message(StompCommand.SEND, HEARTBEAT_DEST, null, sessionAttributes);

        assertThat(interceptor.preSend(msg, null)).isNotNull();
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("최소 간격보다 빠르게 연속 도착한 heartbeat SEND는 드롭되고 지표에 기록된다")
    void heartbeatSend_tooFast_dropped() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        interceptor.preSend(message(StompCommand.SEND, HEARTBEAT_DEST, null, sessionAttributes), null);

        Message<?> second = message(StompCommand.SEND, HEARTBEAT_DEST, null, sessionAttributes);
        assertThat(interceptor.preSend(second, null)).isNull();
        verify(metrics).recordHeartbeatDropped();
    }

    @Test
    @DisplayName("최소 간격이 지난 뒤 도착한 heartbeat SEND는 통과한다")
    void heartbeatSend_afterInterval_passes() throws InterruptedException {
        when(properties.getHeartbeatInterval()).thenReturn(Duration.ofMillis(40)); // 하한 20ms
        Map<String, Object> sessionAttributes = new HashMap<>();
        interceptor.preSend(message(StompCommand.SEND, HEARTBEAT_DEST, null, sessionAttributes), null);

        Thread.sleep(30);

        Message<?> second = message(StompCommand.SEND, HEARTBEAT_DEST, null, sessionAttributes);
        assertThat(interceptor.preSend(second, null)).isNotNull();
    }

    // chat SEND

    @Test
    @DisplayName("최소 간격보다 빠른 chat SEND는 드롭되고, heartbeat 카운터와는 분리된 지표로 기록된다")
    void chatSend_tooFast_dropped_separateFromHeartbeat() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        interceptor.preSend(message(StompCommand.SEND, CHAT_SEND_DEST, null, sessionAttributes), null);

        Message<?> second = message(StompCommand.SEND, CHAT_SEND_DEST, null, sessionAttributes);
        assertThat(interceptor.preSend(second, null)).isNull();
        verify(metrics).recordChatSendDropped();
        verify(metrics, org.mockito.Mockito.never()).recordHeartbeatDropped();
    }

    @Test
    @DisplayName("heartbeat와 chat SEND는 서로 다른 타이머를 쓰므로 heartbeat 직후 chat SEND는 드롭되지 않는다")
    void heartbeatAndChatSend_useIndependentTimers() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        interceptor.preSend(message(StompCommand.SEND, HEARTBEAT_DEST, null, sessionAttributes), null);

        Message<?> chatMsg = message(StompCommand.SEND, CHAT_SEND_DEST, null, sessionAttributes);
        assertThat(interceptor.preSend(chatMsg, null)).isNotNull();
    }

    // watch SUBSCRIBE

    @Test
    @DisplayName("최소 간격보다 빠른 watch 재구독은 드롭된다")
    void watchSubscribe_tooFast_dropped() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        interceptor.preSend(message(StompCommand.SUBSCRIBE, WATCH_SUB_DEST, "sub-1", sessionAttributes), null);

        Message<?> second =
            message(StompCommand.SUBSCRIBE, WATCH_SUB_DEST, "sub-2", sessionAttributes);
        assertThat(interceptor.preSend(second, null)).isNull();
        verify(metrics).recordWatchSubscribeDropped();
    }

    @Test
    @DisplayName("페이지 진입 시 1회 구독만 하는 정상 흐름은 걸리지 않는다")
    void watchSubscribe_singleNormalSubscribe_passes() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        Message<?> msg = message(StompCommand.SUBSCRIBE, WATCH_SUB_DEST, "sub-1", sessionAttributes);

        assertThat(interceptor.preSend(msg, null)).isNotNull();
        verifyNoInteractions(metrics);
    }

    // chat SUBSCRIBE 개수 상한

    @Test
    @DisplayName("상한 이내의 서로 다른 chat 구독은 모두 통과한다")
    void chatSubscribe_withinLimit_passes() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        Message<?> first = message(StompCommand.SUBSCRIBE, CHAT_SUB_DEST, "sub-1", sessionAttributes);
        Message<?> second = message(StompCommand.SUBSCRIBE, CHAT_SUB_DEST, "sub-2", sessionAttributes);

        assertThat(interceptor.preSend(first, null)).isNotNull();
        assertThat(interceptor.preSend(second, null)).isNotNull();
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("상한(2개)을 넘는 세 번째 chat 구독은 드롭되고 지표에 기록된다")
    void chatSubscribe_exceedingLimit_dropped() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        interceptor.preSend(message(StompCommand.SUBSCRIBE, CHAT_SUB_DEST, "sub-1", sessionAttributes), null);
        interceptor.preSend(message(StompCommand.SUBSCRIBE, CHAT_SUB_DEST, "sub-2", sessionAttributes), null);

        Message<?> third = message(StompCommand.SUBSCRIBE, CHAT_SUB_DEST, "sub-3", sessionAttributes);
        assertThat(interceptor.preSend(third, null)).isNull();
        verify(metrics).recordChatSubscribeDropped();
    }

    @Test
    @DisplayName("UNSUBSCRIBE로 해제하면 그만큼 상한 여유가 회복되어 다음 구독이 통과한다")
    void chatSubscribe_afterUnsubscribe_freesCapacity() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        interceptor.preSend(message(StompCommand.SUBSCRIBE, CHAT_SUB_DEST, "sub-1", sessionAttributes), null);
        interceptor.preSend(message(StompCommand.SUBSCRIBE, CHAT_SUB_DEST, "sub-2", sessionAttributes), null);

        // sub-1 해제
        interceptor.preSend(message(StompCommand.UNSUBSCRIBE, null, "sub-1", sessionAttributes), null);

        Message<?> third = message(StompCommand.SUBSCRIBE, CHAT_SUB_DEST, "sub-3", sessionAttributes);
        assertThat(interceptor.preSend(third, null)).isNotNull();
    }

    // fail-open

    @Test
    @DisplayName("세션 attribute가 없으면 어떤 목적지든 통과한다(fail-open)")
    void noSessionAttributes_failsOpen() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(HEARTBEAT_DEST);
        Message<?> msg = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(msg, null)).isNotNull();
    }

    // 범위 밖 목적지

    @Test
    @DisplayName("DM 목적지는 어떤 제한도 거치지 않고 통과한다")
    void dmDestination_isNotRateLimited() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        Message<?> msg = message(StompCommand.SEND, DM_SEND_DEST, null, sessionAttributes);

        assertThat(interceptor.preSend(msg, null)).isNotNull();
        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("watch/chat과 무관한 UNSUBSCRIBE는 예외 없이 무시된다")
    void unsubscribe_withoutMapping_doesNothing() {
        Map<String, Object> sessionAttributes = new HashMap<>();
        Message<?> msg = message(StompCommand.UNSUBSCRIBE, null, "sub-unknown", sessionAttributes);

        assertThat(interceptor.preSend(msg, null)).isNotNull();
    }
}
