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

    private SessionDisconnectEvent disconnectEvent(Authentication principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setSessionId(SESSION_ID);
        accessor.setLeaveMutable(true);
        if (principal != null) {
            accessor.setUser(principal);
        }
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new SessionDisconnectEvent(this, message, "session-0", CloseStatus.NORMAL, principal);
    }

    @Test
    @DisplayName("연결이 끊기면 소유권을 확인하여 활성 세션을 종료하고 LEAVE 브로드캐스트")
    void onDisconnect_success_endsSessionAndBroadcastsLeave() {
        // given
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));

        // 원자적 삭제 성공 모킹
        when(watchingSessionService.end(WATCHER_ID, SESSION_ID)).thenReturn(true);

        SessionDisconnectEvent event = disconnectEvent(principalOf(WATCHER_ID));

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID);
        verify(watchingSessionBroadcaster).broadcastLeave(dto, CONTENT_ID);
    }

    @Test
    @DisplayName("세션 소유권이 다르면(삭제 실패) 삭제 알림 브로드캐스트를 건너뜀")
    void onDisconnect_skipsBroadcast_whenActuallyDeletedIsFalse() {
        // given
        WatchingSessionDto dto = dtoFixture(CONTENT_ID);
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.of(dto));

        // 원자적 삭제 실패(다른 탭에서 덮어씌움 등) 모킹
        when(watchingSessionService.end(WATCHER_ID, SESSION_ID)).thenReturn(false);

        SessionDisconnectEvent event = disconnectEvent(principalOf(WATCHER_ID));

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService).end(WATCHER_ID, SESSION_ID);
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("활성 시청 세션이 없으면 종료 처리(end 호출 자체)를 생략")
    void onDisconnect_success_skipsEndWhenNoActiveSession() {
        // given
        when(watchingSessionService.get(WATCHER_ID)).thenReturn(Optional.empty());

        SessionDisconnectEvent event = disconnectEvent(principalOf(WATCHER_ID));

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService, never()).end(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("Principal이 없으면 세션 종료 자체를 시도하지 않음")
    void onDisconnect_success_ignoresWhenPrincipalMissing() {
        // given
        SessionDisconnectEvent event = disconnectEvent(null);

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService, never()).get(any());
        verify(watchingSessionService, never()).end(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("Principal의 이름이 올바른 UUID 형식이 아니면 무시")
    void onDisconnect_success_ignoresWhenPrincipalNameIsInvalidUUID() {
        // given
        Authentication invalidPrincipal = UsernamePasswordAuthenticationToken
            .authenticated("invalid-user-id", null, List.of());

        SessionDisconnectEvent event = disconnectEvent(invalidPrincipal);

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService, never()).get(any());
        verify(watchingSessionService, never()).end(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }
}
