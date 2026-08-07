package com.mopl.watchingsession.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private SessionUnsubscribeEvent createUnsubscribeEvent(String subscriptionId, Authentication principal, Map<String, Object> sessionAttributes) {
        StompHeaderAccessor unsubscribeAccessor = StompHeaderAccessor.create(StompCommand.UNSUBSCRIBE);
        unsubscribeAccessor.setSubscriptionId(subscriptionId);
        unsubscribeAccessor.setSessionId(SESSION_ID);
        unsubscribeAccessor.setSessionAttributes(sessionAttributes);
        unsubscribeAccessor.setLeaveMutable(true);
        if (principal != null) {
            unsubscribeAccessor.setUser(principal);
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], unsubscribeAccessor.getMessageHeaders());
        return new SessionUnsubscribeEvent(this, message);
    }

    private SessionUnsubscribeEvent unsubscribeEventAfterResubscribe(
        UUID contentId, String staleSubscriptionId, String newSubscriptionId,
        String unsubscribeSubscriptionId, Authentication principal
    ) {
        Map<String, Object> sharedSessionAttributes = new HashMap<>();

        StompHeaderAccessor firstSubscribeAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        firstSubscribeAccessor.setSubscriptionId(staleSubscriptionId);
        firstSubscribeAccessor.setSessionAttributes(sharedSessionAttributes);
        WatchSubscriptionAttributes.put(firstSubscribeAccessor, contentId);

        StompHeaderAccessor secondSubscribeAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        secondSubscribeAccessor.setSubscriptionId(newSubscriptionId);
        secondSubscribeAccessor.setSessionAttributes(sharedSessionAttributes);
        WatchSubscriptionAttributes.put(secondSubscribeAccessor, contentId);

        return createUnsubscribeEvent(unsubscribeSubscriptionId, principal, sharedSessionAttributes);
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
    @DisplayName("시청 토픽을 UNSUBSCRIBE하면 소유권 확인 후 세션을 종료하고 LEAVE를 브로드캐스트")
    void onUnsubscribe_success_endsSessionAndBroadcastsLeave() {
        // given
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));

        // 원자적 삭제 성공(true 반환) 모킹
        when(watchingSessionService.end(WATCHER_ID, SESSION_ID)).thenReturn(true);

        SessionUnsubscribeEvent event = unsubscribeEventWithMapping(CONTENT_ID, "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID);
        verify(watchingSessionBroadcaster).broadcastLeave(dto, CONTENT_ID);
    }

    @Test
    @DisplayName("end()가 삭제에 실패(다른 연결로 소유권 이전됨)하면 브로드캐스트를 전송하지 않음 (중복 알림 방어)")
    void onUnsubscribe_skipsBroadcast_whenActuallyDeletedIsFalse() {
        // given
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));

        // 원자적 삭제 실패(false 반환) 모킹 -> 다른 탭으로 덮어씌워짐 등을 모사
        when(watchingSessionService.end(WATCHER_ID, SESSION_ID)).thenReturn(false);

        SessionUnsubscribeEvent event = unsubscribeEventWithMapping(CONTENT_ID, "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onUnsubscribe(event);

        // then
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID); // 호출은 됨
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any()); // 알림은 안나감
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
        verify(watchingSessionService, never()).end(any(), any());
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
        verify(watchingSessionService, never()).end(any(), any());
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
        verify(watchingSessionService, never()).end(any(), any());
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
        verify(watchingSessionService, never()).end(any(), any());
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
        verify(watchingSessionService, never()).end(any(), any());
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
        verify(watchingSessionService, never()).end(any(), any());
    }

    @Test
    @DisplayName("같은 연결에서 구독을 갈아탄 뒤 이전 구독의 UNSUBSCRIBE가 도착하면 무동작 처리")
    void onUnsubscribe_success_ignoresStaleSubscriptionAfterResubscribe() {
        // given: sub-1로 먼저 구독한 뒤, 같은 연결에서 sub-2로 재구독
        SessionUnsubscribeEvent staleEvent = unsubscribeEventAfterResubscribe(
            CONTENT_ID, "sub-1", "sub-2", "sub-1", principalOf(WATCHER_ID));

        // when: 뒤늦게 도착한 sub-1의 UNSUBSCRIBE 처리
        listener.onUnsubscribe(staleEvent);

        // then: DB 조회조차 하지 않고 완전히 무동작 처리되어야 함
        verify(watchingSessionService, never()).get(any());
        verify(watchingSessionService, never()).end(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("현재 활성 구독의 UNSUBSCRIBE는 재구독 이후에도 정상적으로 세션을 종료")
    void onUnsubscribe_success_endsSessionForCurrentActiveSubscriptionAfterResubscribe() {
        // given
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));
        when(watchingSessionService.end(WATCHER_ID, SESSION_ID)).thenReturn(true);

        SessionUnsubscribeEvent currentEvent = unsubscribeEventAfterResubscribe(
            CONTENT_ID, "sub-1", "sub-2", "sub-2", principalOf(WATCHER_ID));

        // when: 현재 활성 구독(sub-2)의 정상적인 UNSUBSCRIBE
        listener.onUnsubscribe(currentEvent);

        // then
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID);
        verify(watchingSessionBroadcaster).broadcastLeave(dto, CONTENT_ID);
    }

    @Test
    @DisplayName("같은 연결에서 sub-1의 낡은 UNSUBSCRIBE를 무시한 뒤에도, 이어지는 sub-2의 정상 UNSUBSCRIBE는 세션을 종료함")
    void onUnsubscribe_success_stillEndsCurrentSessionAfterIgnoringStaleUnsubscribeInSequence() {
        // given: sub-1 -> sub-2로 재구독된 연결 상태를 재현
        Map<String, Object> sharedSessionAttributes = new HashMap<>();

        StompHeaderAccessor firstSubscribeAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        firstSubscribeAccessor.setSubscriptionId("sub-1");
        firstSubscribeAccessor.setSessionAttributes(sharedSessionAttributes);
        WatchSubscriptionAttributes.put(firstSubscribeAccessor, CONTENT_ID);

        StompHeaderAccessor secondSubscribeAccessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        secondSubscribeAccessor.setSubscriptionId("sub-2");
        secondSubscribeAccessor.setSessionAttributes(sharedSessionAttributes);
        WatchSubscriptionAttributes.put(secondSubscribeAccessor, CONTENT_ID);

        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));
        when(watchingSessionService.end(WATCHER_ID, SESSION_ID)).thenReturn(true);

        SessionUnsubscribeEvent staleEvent = createUnsubscribeEvent("sub-1", principalOf(WATCHER_ID), sharedSessionAttributes);
        SessionUnsubscribeEvent currentEvent = createUnsubscribeEvent("sub-2", principalOf(WATCHER_ID), sharedSessionAttributes);

        // when: 같은 리스너 인스턴스에서 sub-1 UNSUBSCRIBE 먼저 처리, 이어서 sub-2 UNSUBSCRIBE 처리
        listener.onUnsubscribe(staleEvent);
        listener.onUnsubscribe(currentEvent);

        // then: sub-1 처리로 인한 부수효과가 전혀 없어야 하고, sub-2만 정상 종료되어야 함
        verify(watchingSessionService, times(1)).end(WATCHER_ID, SESSION_ID);
        verify(watchingSessionBroadcaster, times(1)).broadcastLeave(dto, CONTENT_ID);
    }
}
