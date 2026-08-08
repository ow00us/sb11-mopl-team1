package com.mopl.watchingsession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.websocket.StompErrorFrameSender;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import com.mopl.watchingsession.service.WatchingSessionService.ReplacedSession;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Mock
    StompErrorFrameSender errorFrameSender;

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

    private ReplacedSession replacedSession(WatchingSessionDto session, WatchingSessionDto previous) {
        return new ReplacedSession(session, previous);
    }

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
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0"))
            .thenReturn(replacedSession(dto, null));

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService).start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0");
        verify(watchingSessionBroadcaster).broadcastJoin(dto, CONTENT_ID);
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
        verifyNoInteractions(errorFrameSender);
    }

    @Test
    @DisplayName("기존 활성 세션(A)이 있는 상태에서 다른 콘텐츠(B)를 구독 성공 시 기존 방(A)에 퇴장 알림을 먼저 보냄")
    void onSubscribe_broadcastsLeaveToPrevContent_whenSubscribingToNewContent() {
        // given
        WatchingSessionDto prevSession = dtoFixture(PREV_CONTENT_ID);
        WatchingSessionDto newSession = dtoFixture(CONTENT_ID);

        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0"))
            .thenReturn(replacedSession(newSession, prevSession));

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionBroadcaster).broadcastLeave(prevSession, PREV_CONTENT_ID);
        verify(watchingSessionService).start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0");
        verify(watchingSessionBroadcaster).broadcastJoin(newSession, CONTENT_ID);
        verify(watchingSessionService, never()).get(any()); // 리스너가 별도로 get()을 호출하지 않음
    }

    @Test
    @DisplayName("기존 활성 세션과 동일한 콘텐츠를 다시 구독하면 퇴장 알림을 보내지 않음")
    void onSubscribe_skipsLeaveBroadcast_whenSubscribingToSameContent() {
        // given
        WatchingSessionDto currentSession = dtoFixture(CONTENT_ID);

        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0"))
            .thenReturn(replacedSession(currentSession, currentSession));

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
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0"))
            .thenReturn(replacedSession(dto, null));

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        StompHeaderAccessor lookupAccessor = StompHeaderAccessor.wrap(event.getMessage());
        assertThat(WatchSubscriptionAttributes.consume(lookupAccessor).contentId()).isEqualTo(CONTENT_ID);
    }

    @Test
    @DisplayName("start() 처리 중 BusinessException 발생 시 인메모리 매핑을 롤백하고 클라이언트에 ERROR 프레임 전송")
    void onSubscribe_rollback_whenStartThrowsBusinessException() {
        // given
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-123", principalOf(WATCHER_ID));

        BusinessException exception = new BusinessException(ErrorCode.CONTENT_NOT_FOUND);
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-123"))
            .thenThrow(exception);

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).end(any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());

        StompHeaderAccessor lookupAccessor = StompHeaderAccessor.wrap(event.getMessage());
        assertThat(WatchSubscriptionAttributes.consume(lookupAccessor).hasMapping()).isFalse();

        verify(errorFrameSender).send(
            eq(event.getMessage()),
            eq("BusinessException"),
            eq(ErrorCode.CONTENT_NOT_FOUND),
            eq(ErrorCode.CONTENT_NOT_FOUND.getMessage()),
            eq(exception.getDetails())
        );
    }

    @Test
    @DisplayName("start() 처리 중 BusinessException이 아닌 예외 발생 시 유령 구독 방지를 위해 INTERNAL_ERROR 발송 및 매핑 롤백 (#137)")
    void onSubscribe_sendsErrorFrame_whenStartThrowsNonBusinessException() {
        // given
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-123", principalOf(WATCHER_ID));

        RuntimeException unexpected = new IllegalStateException("예상 못한 오류");
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-123"))
            .thenThrow(unexpected);

        // when
        listener.onSubscribe(event);

        // then
        StompHeaderAccessor lookupAccessor = StompHeaderAccessor.wrap(event.getMessage());
        assertThat(WatchSubscriptionAttributes.consume(lookupAccessor).hasMapping()).isFalse();

        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());

        verify(errorFrameSender).send(
            eq(event.getMessage()),
            eq("IllegalStateException"),
            eq(ErrorCode.INTERNAL_ERROR),
            eq(ErrorCode.INTERNAL_ERROR.getMessage()),
            eq(Map.of())
        );
    }

    @Test
    @DisplayName("SubscriptionId 헤더가 없으면 매핑 실패로 간주하고 로직 중단")
    void onSubscribe_failsMapping_whenSubscriptionIdIsMissing() {
        // given
        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", null, principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).start(any(), any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("WebSocket 세션 속성이 없으면 매핑 실패로 간주하고 로직 중단")
    void onSubscribe_failsMapping_whenSessionAttributesIsNull() {
        // given
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/sub/contents/" + CONTENT_ID + "/watch");
        accessor.setSubscriptionId("sub-0");
        accessor.setUser(principalOf(WATCHER_ID));
        // SessionAttributes를 세팅하지 않아 내부적으로 null이 반환되도록 유도
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        SessionSubscribeEvent event = new SessionSubscribeEvent(this, message);

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionService, never()).start(any(), any(), any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
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
        verify(watchingSessionService, never()).start(any(), any(), any(), any());
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
        verify(watchingSessionService, never()).start(any(), any(), any(), any());
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
        verify(watchingSessionService, never()).start(any(), any(), any(), any());
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
        verify(watchingSessionService, never()).start(any(), any(), any(), any());
    }

    @Test
    @DisplayName("재구독(sub-1 -> sub-2)에서 sub-2의 start()가 실패해도 sub-1은 계속 활성 상태로 남아, 이후 sub-1의 UNSUBSCRIBE가 정상적으로 시청 세션을 종료함")
    void onSubscribe_keepsPreviousSubscriptionActive_whenResubscribeStartFails() {
        // given: sub-1로 먼저 정상 구독해 활성 상태로 만든다.
        WatchingSessionDto sub1Session = dtoFixture(PREV_CONTENT_ID);
        WatchingSessionService.ReplacedSession sub1Replaced =
            new WatchingSessionService.ReplacedSession(sub1Session, null);
        when(watchingSessionService.start(WATCHER_ID, PREV_CONTENT_ID, SESSION_ID, "sub-1"))
            .thenReturn(sub1Replaced);

        SessionSubscribeEvent sub1Event = subscribeEvent(
            "/sub/contents/" + PREV_CONTENT_ID + "/watch", "sub-1", principalOf(WATCHER_ID));
        listener.onSubscribe(sub1Event);

        // when: 같은 연결에서 sub-2로 재구독을 시도하지만 start()가 실패
        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-2"))
            .thenThrow(new BusinessException(ErrorCode.CONTENT_NOT_FOUND));

        SessionSubscribeEvent sub2Event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-2", principalOf(WATCHER_ID));
        listener.onSubscribe(sub2Event);

        // then: sub-2는 활성으로 전환되지 않았어야 하므로, sub-1의 UNSUBSCRIBE는 여전히 활성 구독으로 판정되어야 한다.
        StompHeaderAccessor sub1UnsubscribeAccessor = StompHeaderAccessor.wrap(sub1Event.getMessage());
        assertThat(WatchSubscriptionAttributes.currentActiveSubscriptionId(sub1UnsubscribeAccessor)).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("재구독 중 enrich 실패로 보상 삭제되면, 직전 콘텐츠(A)의 다른 시청자에게 LEAVE를 브로드캐스트")
    void onSubscribe_broadcastsLeaveToPrevContent_whenStartFailsAfterCompensation() {
        // given
        WatchingSessionDto endedPrevious = dtoFixture(PREV_CONTENT_ID);
        BusinessException enrichFailure = new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        WatchingSessionService.StartFailedException startFailed =
            new WatchingSessionService.StartFailedException(enrichFailure, endedPrevious);

        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0"))
            .thenThrow(startFailed);

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionBroadcaster).broadcastLeave(endedPrevious, PREV_CONTENT_ID);
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());

        StompHeaderAccessor lookupAccessor = StompHeaderAccessor.wrap(event.getMessage());
        assertThat(WatchSubscriptionAttributes.consume(lookupAccessor).hasMapping()).isFalse();

        // 클라이언트로 나가는 ERROR 프레임은 StartFailedException이 아닌 원래 원인(cause) 기준이어야 함
        verify(errorFrameSender).send(
            eq(event.getMessage()),
            eq("BusinessException"),
            eq(ErrorCode.RESOURCE_NOT_FOUND),
            eq(ErrorCode.RESOURCE_NOT_FOUND.getMessage()),
            eq(enrichFailure.getDetails())
        );
    }

    @Test
    @DisplayName("보상 삭제가 소유권 불일치로 스킵되면(endedPrevious=null) LEAVE를 보내지 않음")
    void onSubscribe_skipsLeaveBroadcast_whenStartFailsWithNoEndedPrevious() {
        // given: 보상 delete가 실제로는 수행되지 않은 경우(예: 그 사이 다른 재구독이 소유권을 가져간 레이스)
        BusinessException enrichFailure = new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        WatchingSessionService.StartFailedException startFailed =
            new WatchingSessionService.StartFailedException(enrichFailure, null);

        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0"))
            .thenThrow(startFailed);

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastJoin(any(), any());
        verify(errorFrameSender).send(
            eq(event.getMessage()), eq("BusinessException"),
            eq(ErrorCode.RESOURCE_NOT_FOUND), eq(ErrorCode.RESOURCE_NOT_FOUND.getMessage()), any());
    }

    @Test
    @DisplayName("보상 삭제 후 원인이 인프라 예외였다면, LEAVE 브로드캐스트 후 INTERNAL_ERROR 프레임이 원인 클래스명으로 발송됨")
    void onSubscribe_sendsInternalError_withOriginalCauseName_whenStartFailedWrapsInfraException() {
        // given
        WatchingSessionDto endedPrevious = dtoFixture(PREV_CONTENT_ID);
        RuntimeException infraFailure = new IllegalStateException("DB 커넥션 끊김");
        WatchingSessionService.StartFailedException startFailed =
            new WatchingSessionService.StartFailedException(infraFailure, endedPrevious);

        when(watchingSessionService.start(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-0"))
            .thenThrow(startFailed);

        SessionSubscribeEvent event = subscribeEvent(
            "/sub/contents/" + CONTENT_ID + "/watch", "sub-0", principalOf(WATCHER_ID));

        // when
        listener.onSubscribe(event);

        // then
        verify(watchingSessionBroadcaster).broadcastLeave(endedPrevious, PREV_CONTENT_ID);
        verify(errorFrameSender).send(
            eq(event.getMessage()),
            eq("IllegalStateException"), // StartFailedException이 아니라 원래 원인의 클래스명이어야 함
            eq(ErrorCode.INTERNAL_ERROR),
            eq(ErrorCode.INTERNAL_ERROR.getMessage()),
            eq(Map.of())
        );
    }
}
