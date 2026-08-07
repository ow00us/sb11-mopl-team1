package com.mopl.watchingsession.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.UserSummary;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@ExtendWith(MockitoExtension.class)
public class WatchingSessionDisconnectListenerTest {

    @Mock
    WatchingSessionService watchingSessionService;

    @Mock
    WatchingSessionBroadcaster watchingSessionBroadcaster;

    @InjectMocks
    WatchingSessionDisconnectListener listener;

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SESSION_ID = "session-0";

    private WatchingSessionDto dtoFixture(UUID contentId) {
        return new WatchingSessionDto(
            UUID.randomUUID(),
            new UserSummary(WATCHER_ID, "테스트유저", null),
            new ContentSummary(contentId, "movie", "테스트콘텐츠", "설명", null, List.of(), 0.0, 0),
            Instant.now()
        );
    }

    private Authentication principalOf(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of());
    }

    // 모든 상태(sessionId, attributes)를 자유롭게 주입할 수 있도록 헬퍼 메서드 개선
    private SessionDisconnectEvent createDisconnectEvent(
        String sessionId, Authentication principal, Map<String, Object> sessionAttributes
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(sessionId);
        accessor.setLeaveMutable(true);
        accessor.setSessionAttributes(sessionAttributes);
        if (principal != null) {
            accessor.setUser(principal);
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, sessionId, CloseStatus.NORMAL, principal);
    }

    @Test
    @DisplayName("연결이 끊기면 활성 구독 ID를 확인하여 세션을 종료하고 LEAVE 브로드캐스트 (정상 동작)")
    void onDisconnect_success_endsSessionAndBroadcastsLeave() {
        // given: 활성화된 구독(sub-active)이 있는 상태 구성
        Map<String, Object> attributes = new HashMap<>();
        StompHeaderAccessor subAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subAccessor.setSubscriptionId("sub-active");
        subAccessor.setSessionAttributes(attributes);
        WatchSubscriptionAttributes.put(subAccessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(subAccessor);

        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));

        // 소유권(sub-active) 검증 통과 모킹
        when(watchingSessionService.end(WATCHER_ID, SESSION_ID, "sub-active")).thenReturn(true);

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, principalOf(WATCHER_ID), attributes);

        // when
        listener.onDisconnect(event);

        // then: 실제 활성 구독 ID가 서비스로 전달되어야 함
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID, "sub-active");
        verify(watchingSessionBroadcaster).broadcastLeave(dto, CONTENT_ID);
    }

    @Test
    @DisplayName("세션 소유권이 다르면(삭제 실패) 삭제 알림 브로드캐스트를 건너뜀")
    void onDisconnect_skipsBroadcast_whenActuallyDeletedIsFalse() {
        // given: 활성화된 구독(sub-active)은 있으나, 서비스에서 end()가 실패하는(다른 탭으로 소유권 넘어감) 시나리오
        Map<String, Object> attributes = new HashMap<>();
        StompHeaderAccessor subAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subAccessor.setSubscriptionId("sub-active");
        subAccessor.setSessionAttributes(attributes);
        WatchSubscriptionAttributes.put(subAccessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(subAccessor);

        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));

        when(watchingSessionService.end(WATCHER_ID, SESSION_ID, "sub-active")).thenReturn(false);

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, principalOf(WATCHER_ID), attributes);

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID, "sub-active");
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("활성 구독 기록이 없는 연결이 끊기면(watch 토픽 비구독자) subscriptionId는 null로 전달된다")
    void onDisconnect_success_passesNullSubscriptionId_whenNeverSubscribedToWatchTopic() {
        // given: attributes는 존재하지만 시청 세션(watch)을 activate한 적이 없는 상태
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));

        when(watchingSessionService.end(WATCHER_ID, SESSION_ID, null)).thenReturn(false);

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, principalOf(WATCHER_ID), new HashMap<>());

        // when
        listener.onDisconnect(event);

        // then: 활성 구독이 없으므로 null이 전달됨
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID, null);
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("활성 시청 세션이 없으면(DB 조회 결과 없음) 종료 처리(end 호출 자체)를 생략")
    void onDisconnect_success_skipsEndWhenNoActiveSession() {
        // given
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.empty());

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, principalOf(WATCHER_ID), new HashMap<>());

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService, never()).end(any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("Principal이 없으면 세션 종료 자체를 시도하지 않음")
    void onDisconnect_success_ignoresWhenPrincipalMissing() {
        // given
        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, null, new HashMap<>());

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService, never()).get(any());
        verify(watchingSessionService, never()).end(any(), any(), any());
    }

    @Test
    @DisplayName("Principal의 이름이 올바른 UUID 형식이 아니면 무시")
    void onDisconnect_success_ignoresWhenPrincipalNameIsInvalidUUID() {
        // given
        Authentication invalidPrincipal = UsernamePasswordAuthenticationToken
            .authenticated("invalid-user-id", null, List.of());

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, invalidPrincipal, new HashMap<>());

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService, never()).get(any());
        verify(watchingSessionService, never()).end(any(), any(), any());
    }

    @Test
    @DisplayName("재구독(sub-1 -> sub-2) 이후 연결이 끊기면, 마지막으로 활성화된 sub-2를 기준으로 종료 처리한다")
    void onDisconnect_success_usesLatestActivatedSubscriptionId_afterResubscribe() {
        // given
        Map<String, Object> sharedSessionAttributes = new HashMap<>();

        StompHeaderAccessor sub1Accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        sub1Accessor.setSubscriptionId("sub-1");
        sub1Accessor.setSessionAttributes(sharedSessionAttributes);
        WatchSubscriptionAttributes.put(sub1Accessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub1Accessor);

        StompHeaderAccessor sub2Accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        sub2Accessor.setSubscriptionId("sub-2");
        sub2Accessor.setSessionAttributes(sharedSessionAttributes);
        WatchSubscriptionAttributes.put(sub2Accessor, CONTENT_ID);
        WatchSubscriptionAttributes.activate(sub2Accessor);

        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));
        when(watchingSessionService.end(WATCHER_ID, SESSION_ID, "sub-2")).thenReturn(true);

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, principalOf(WATCHER_ID), sharedSessionAttributes);

        // when
        listener.onDisconnect(event);

        // then: 가장 마지막으로 activate된 sub-2를 기준으로 end()가 호출되어야 함
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID, "sub-2");
        verify(watchingSessionBroadcaster).broadcastLeave(dto, CONTENT_ID);
    }
}
