package com.mopl.watchingsession.websocket.listener;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.UserSummary;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import com.mopl.watchingsession.websocket.broadcast.WatchingSessionBroadcaster;
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
    @DisplayName("연결이 끊기면 sessionId만으로 종료를 위임하고, 삭제된 스냅샷 기준 DTO로만 LEAVE를 브로드캐스트한다")
    void onDisconnect_success_broadcastsLeave_usingReturnedDtoOnly() {
        // given
        WatchingSessionDto endedSession = dtoFixture(CONTENT_ID);
        when(watchingSessionService.endByConnection(WATCHER_ID, SESSION_ID)).thenReturn(Optional.of(endedSession));

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, principalOf(WATCHER_ID), new HashMap<>());

        // when
        listener.onDisconnect(event);

        // then: 리스너가 별도로 get()을 호출하지 않고, endByConnection()의 반환값만으로 브로드캐스트한다
        verify(watchingSessionService, never()).get(any());
        verify(watchingSessionService).endByConnection(WATCHER_ID, SESSION_ID);
        verify(watchingSessionBroadcaster).broadcastLeave(endedSession, CONTENT_ID);
    }

    @Test
    @DisplayName("소유권 확인에 실패하면(빈 Optional) 브로드캐스트를 건너뜀")
    void onDisconnect_skipsBroadcast_whenEndByConnectionReturnsEmpty() {
        // given
        when(watchingSessionService.endByConnection(WATCHER_ID, SESSION_ID)).thenReturn(Optional.empty());

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, principalOf(WATCHER_ID), new HashMap<>());

        // when
        listener.onDisconnect(event);

        // then
        verify(watchingSessionService).endByConnection(WATCHER_ID, SESSION_ID);
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("Principal이 없으면 세션 종료 자체를 시도하지 않음")
    void onDisconnect_success_ignoresWhenPrincipalMissing() {
        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, null, new HashMap<>());

        listener.onDisconnect(event);

        verify(watchingSessionService, never()).endByConnection(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("Principal의 이름이 올바른 UUID 형식이 아니면 무시")
    void onDisconnect_success_ignoresWhenPrincipalNameIsInvalidUUID() {
        Authentication invalidPrincipal = UsernamePasswordAuthenticationToken
            .authenticated("invalid-user-id", null, List.of());
        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, invalidPrincipal, new HashMap<>());

        listener.onDisconnect(event);

        verify(watchingSessionService, never()).endByConnection(any(), any());
        verify(watchingSessionBroadcaster, never()).broadcastLeave(any(), any());
    }

    @Test
    @DisplayName("브로드캐스트가 실패해도 리스너는 예외 없이 종료된다")
    void onDisconnect_doesNotThrow_whenBroadcastFails() {
        WatchingSessionDto endedSession = dtoFixture(CONTENT_ID);
        when(watchingSessionService.endByConnection(WATCHER_ID, SESSION_ID)).thenReturn(Optional.of(endedSession));
        doThrow(new RuntimeException("브로커 전송 실패"))
            .when(watchingSessionBroadcaster).broadcastLeave(endedSession, CONTENT_ID);

        SessionDisconnectEvent event = createDisconnectEvent(SESSION_ID, principalOf(WATCHER_ID), new HashMap<>());

        assertThatNoException().isThrownBy(() -> listener.onDisconnect(event));
        verify(watchingSessionBroadcaster).broadcastLeave(endedSession, CONTENT_ID);
    }
}
