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
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@ExtendWith(MockitoExtension.class)
public class WatchingSessionUnsubscribeListenerTest {

    @Mock
    WatchingSessionService watchingSessionService;

    @Mock
    WatchingSessionBroadcaster watchingSessionBroadcaster;

    @InjectMocks
    WatchingSessionUnsubscribeListener listener;


    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_CONTENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

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

    private SessionUnsubscribeEvent createUnsubscribeEvent(String subscriptionId, Authentication principal, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor unsubscribeAccessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        unsubscribeAccessor.setSubscriptionId(subscriptionId);
        unsubscribeAccessor.setSessionAttributes(sessionAttributes);
        unsubscribeAccessor.setLeaveMutable(true);
        if (principal != null) {
            unsubscribeAccessor.setUser(principal);
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], unsubscribeAccessor.getMessageHeaders());
        return new SessionUnsubscribeEvent(this, message);
    }

    private SessionUnsubscribeEvent unsubscribeEventWithMapping(
        UUID mappedContentId, String subscriptionId, Authentication principal
    ) {
        Map<String, Object> sharedSessionAttributes = new HashMap<>();

        StompHeaderAccessor subscribeAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribeAccessor.setSubscriptionId(subscriptionId);
        subscribeAccessor.setSessionAttributes(sharedSessionAttributes);
        WatchSubscriptionAttributes.put(subscribeAccessor, mappedContentId);

        return createUnsubscribeEvent(subscriptionId, principal, sharedSessionAttributes);
    }

    private SessionUnsubscribeEvent unsubscribeEventWithoutMapping(String subscriptionId, Authentication principal) {
        return createUnsubscribeEvent(subscriptionId, principal, new HashMap<>());
    }

    @Test
    @DisplayName("시청 토픽을 UNSUBSCRIBE하면 세션을 종료하고 LEAVE를 브로드캐스트")
    void onUnsubscribe_success_endsSessionAndBroadcastsLeave() {
        // given
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));

        SessionUnsubscribeEvent event = unsubscribeEventWithMapping(CONTENT_ID, "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService).end(WATCHER_ID);
        verify(watchingSessionBroadcaster).broadcastLeave(dto, CONTENT_ID);
    }

    @Test
    @DisplayName("subscriptionId에 매핑된 contentId가 없으면(시청 토픽 구독이 아니면) 관여하지 않음")
    void onUnsubscribe_success_ignoresWhenNoMapping() {
        // given
        SessionUnsubscribeEvent event = unsubscribeEventWithoutMapping("sub-0", principalOf(WATCHER_ID));

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService, never()).get(any());
        verify(watchingSessionService, never()).end(any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("Principal이 없으면 세션 종료하지 않음")
    void onUnsubscribe_success_ignoresWhenPrincipalMissing() {
        // given
        SessionUnsubscribeEvent event = unsubscribeEventWithMapping(CONTENT_ID, "sub-0", null);

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService, never()).end(any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("활성 세션이 없으면(이미 만료, 삭제됨) 종료 처리 생략")
    void onUnsubscribe_success_ignoresWhenNoActiveSession() {
        // given
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.empty());

        SessionUnsubscribeEvent event = unsubscribeEventWithMapping(CONTENT_ID, "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService, never()).end(any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("매핑된 contentId와 실제 활성 세션의 콘텐츠가 다르면 아무 처리도 하지 않음")
    void onUnsubscribe_success_ignoresWhenMappedContentDiffersFromActiveSession() {
        // given: 매핑은 CONTENT_ID를 가리키지만 그사이 다른 콘텐츠를 구독해 활성 세션은 OTHER_CONTENT_ID로 갱신
        WatchingSessionDto activeSessionForOtherContent = dtoFixture(OTHER_CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(activeSessionForOtherContent));

        SessionUnsubscribeEvent event = unsubscribeEventWithMapping(CONTENT_ID, "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService, never()).end(any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("Principal의 이름이 올바른 UUID 형식이 아니면 무시함")
    void onUnsubscribe_success_ignoresWhenPrincipalNameIsInvalidUUID() {
        // given
        Authentication invalidPrincipal = UsernamePasswordAuthenticationToken
            .authenticated("invalid-user-id", null, List.of());
        SessionUnsubscribeEvent event = unsubscribeEventWithMapping(CONTENT_ID, "sub-0", invalidPrincipal);

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService, never()).end(any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("subscriptionId 헤더가 없는 비정상적인 프레임이면 무시함")
    void onUnsubscribe_success_ignoresWhenSubscriptionIdIsNull() {
        // given (subscriptionId를 null로 세팅)
        SessionUnsubscribeEvent event = unsubscribeEventWithMapping(CONTENT_ID, null, principalOf(WATCHER_ID));

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService, never()).end(any());
    }
}
