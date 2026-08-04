package com.mopl.watchingsession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

@ExtendWith(MockitoExtension.class)
public class WatchingSessionSubscribeListenerTest {

    @Mock
    WatchingSessionService watchingSessionService;

    @Mock
    WatchingSessionBroadcaster watchingSessionBroadcaster;

    @InjectMocks
    WatchingSessionSubscribeListener listener;

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PREV_CONTENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String SESSION_ID = "session-0";

    private WatchingSessionDto dtoFixture(UUID contentId) {
        return new WatchingSessionDto(
            UUID.randomUUID(),
            new UserSummary(WATCHER_ID, "테스트유저", null),
            new ContentSummary(contentId, "movie", "테스트콘텐츠", "설명", null, List.of(), 0.0, 0),
            Instant.now()
        );
    }

    // StompHeaderAccessor.create()로 새로 만든 accessor는 이 맵을 자동으로 갖지 않음
    // 직접 만들어 SUBSCRIBE 프레임에 심어준 뒤 조회용 accessor에도 같은 참조를 넣어 동일 세션 재현
    private SessionSubscribeEvent subscribeEvent(String destination, String subscriptionId, Authentication principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setSessionId(SESSION_ID);
        accessor.setSessionAttributes(new HashMap<>());
        accessor.setLeaveMutable(true);
        if (principal != null) {
            accessor.setUser(principal);
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionSubscribeEvent(this, message);
    }

    private Authentication principalOf(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of());
    }

    @Test
    @DisplayName("시청 토픽을 SUBSCRIBE하면 시청 세션을 시작하고 JOIN을 브로드캐스트")
    void onSubscribe_success_startsSessionAndBroadcastsJoin() {
        // given
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID)).thenReturn(dto);

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService).start(WATCHER_ID, CONTENT_ID, SESSION_ID);
        verify(watchingSessionBroadcaster).broadcastJoin(dto, CONTENT_ID);
    }

    @Test
    @DisplayName("기존 활성 세션(A)이 있는 상태에서 다른 콘텐츠(B)를 구독 성공 시 기존 방(A)에 퇴장 알림을 먼저 보냄")
    void onSubscribe_broadcastsLeaveToPrevContent_whenSubscribingToNewContent() {
        // given
        // 기존에 보고 있던 A 콘텐츠(PREV_CONTENT_ID) 모킹
        WatchingSessionDto prevSession = dtoFixture(PREV_CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(prevSession));

        // 새롭게 구독하려는 B 콘텐츠(CONTENT_ID) 모킹
        WatchingSessionDto newSession = dtoFixture(CONTENT_ID);
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID)).thenReturn(newSession);

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        // 이전 방(A)에 대해서 LEAVE가 먼저 나갔는지 검증
        verify(watchingSessionBroadcaster).broadcastLeave(prevSession, PREV_CONTENT_ID);

        // 새 방(B)에 대해서 DB 세션이 갱신되고 JOIN이 나갔는지 검증
        verify(watchingSessionService).start(WATCHER_ID, CONTENT_ID, SESSION_ID);
        verify(watchingSessionBroadcaster).broadcastJoin(newSession, CONTENT_ID);
    }

    @Test
    @DisplayName("A 콘텐츠 시청 중 B 콘텐츠로 환승을 시도했으나 start()가 실패하면, 기존 A 콘텐츠에 LEAVE 알림을 보내지 않음")
    void onSubscribe_skipsLeaveBroadcastForPrevContent_whenStartFails() {
        // given
        WatchingSessionDto prevSession = dtoFixture(PREV_CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(prevSession));

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-123", principalOf(WATCHER_ID));

        // start() 시 예외 발생 유도
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID))
            .thenThrow(new RuntimeException("새 세션 시작 실패"));

        // when
        assertDoesNotThrow(() -> listener.onSubscribe(event));

        // then: 실패 시 LEAVE도 JOIN도 나가지 않고 기존 세션 정보 유지
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());

        // 롤백 로직은 정상 동작해야 함
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID);
    }

    @Test
    @DisplayName("A 콘텐츠 시청 중 B 콘텐츠로 환승을 시도했으나 매핑이 실패하면, 기존 A 콘텐츠에 LEAVE 알림을 보내지 않음")
    void onSubscribe_skipsLeaveBroadcastForPrevContent_whenMappingFails() {
        // given
        WatchingSessionDto prevSession = dtoFixture(PREV_CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(prevSession));

        // subscriptionId 누락으로 인한 매핑 실패 유도
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", null, principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then: DB 시작 시도조차 하지 않으며, LEAVE 알림도 보내지 않음
        verify(watchingSessionService, never()).start(any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("기존 활성 세션과 동일한 콘텐츠를 다시 구독하면 퇴장 알림을 보내지 않음")
    void onSubscribe_skipsLeaveBroadcast_whenSubscribingToSameContent() {
        // given
        WatchingSessionDto currentSession = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(currentSession));
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID)).thenReturn(currentSession);

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
        verify(watchingSessionBroadcaster).broadcastJoin(currentSession, CONTENT_ID);
    }

    @Test
    @DisplayName("시청 토픽 구독 시 subscriptionId-contentId 매핑을 세션 attribute에 저장")
    void onSubscribe_success_storesSubscriptionMapping() {
        // given
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID)).thenReturn(dto);

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-42", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then = 같은 세션 attribute 맵에서 방금 저장한 매핑을 그대로 조회할 수 있어야 함
        StompHeaderAccessor lookupAccessor = StompHeaderAccessor.wrap(event.getMessage());
        assertThat(WatchSubscriptionAttributes.remove(lookupAccessor)).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("시청 토픽(/watch)이 아닌 다른 구독은 관여하지 않음")
    void onSubscribe_success_ignoreNonWatchDestination() {
        // given
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/chat", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).start(any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
    }

    @Test
    @DisplayName("Principal이 없으면 세션을 시작하지 않음")
    void onSubscribe_success_ignoresWhenPrincipalMissing() {
        // given
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", null);

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).start(any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
    }

    @Test
    @DisplayName("destination이 contentId가 올바른 UUID 형식이 아니면 무시함")
    void onSubscribe_ignoresWhenContentIdIsInvalid() {
        // given
        String invalidContentId = "not-a-uuid-format";
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + invalidContentId + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).start(any(), any(), any());
    }

    @Test
    @DisplayName("Principal의 인증 이름이 UUID 형식이 아니면(비정상 토큰) 무시함")
    void onSubscribe_ignoresWhenPrincipalNameIsInvalidUUID() {
        // given
        Authentication invalidPrincipal = UsernamePasswordAuthenticationToken
            .authenticated("invalid-user-id", null, List.of());

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", invalidPrincipal);

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).start(any(), any(), any());
    }

    @Test
    @DisplayName("WebSocket 세션 속성이 없으면 매핑 실패로 간주하고 로직 중단")
    void onSubscribe_failsMapping_whenSessionAttributesIsNull() {
        // given: SessionAttributes가 null인 비정상 이벤트
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/sub/contents/" + CONTENT_ID + "/watch");
        accessor.setSubscriptionId("sub-0");
        accessor.setUser(principalOf(WATCHER_ID));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionSubscribeEvent event = new SessionSubscribeEvent(this, message);

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).start(any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
    }

    @Test
    @DisplayName("SubscriptionId 헤더가 없으면 매핑 실패로 간주하고 로직 중단")
    void onSubscribe_failsMapping_whenSubscriptionIdIsMissing() {
        // given: subscriptionId로 null 주입
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", null, principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).start(any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
    }

    @Test
    @DisplayName("start() 처리 중 예외 발생 시 STOMP 예외 전파를 막고 인메모리 및 DB 정리 수행")
    void onSubscribe_rollback_whenStartThrowsException() {
        // given
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-123", principalOf(WATCHER_ID));

        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID))
            .thenThrow(new RuntimeException("시청 세션 시작 실패 흉내"));

        // when
        assertDoesNotThrow(() -> listener.onSubscribe(event));

        // then
        // 예외 발생 시 end에도 sessionId가 정확히 넘어가는지 검증
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID);
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());

        StompHeaderAccessor lookupAccessor = StompHeaderAccessor.wrap(event.getMessage());
        assertThat(WatchSubscriptionAttributes.remove(lookupAccessor)).isNull();
    }
}
