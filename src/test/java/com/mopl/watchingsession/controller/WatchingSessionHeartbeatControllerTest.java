package com.mopl.watchingsession.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mopl.watchingsession.service.WatchingSessionService;
import com.mopl.watchingsession.websocket.stompsession.WatchSubscriptionAttributes;
import java.security.Principal;
import java.util.HashMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class WatchingSessionHeartbeatControllerTest {

    @Mock
    WatchingSessionService watchingSessionService;

    @InjectMocks
    WatchingSessionHeartbeatController controller;

    private static final UUID WATCHER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SESSION_ID = "session-123";

    private Principal principalOf(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, java.util.List.of());
    }

    private SimpMessageHeaderAccessor createAccessor(String sessionId, String activeSubscriptionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionId(sessionId);
        accessor.setSessionAttributes(new HashMap<>());
        if (activeSubscriptionId != null) {
            accessor.getSessionAttributes()
                .put(WatchSubscriptionAttributes.ACTIVE_SUBSCRIPTION_ID_ATTRIBUTE_KEY, activeSubscriptionId);
        }
        return accessor;
    }

    @Test
    @DisplayName("정상 Principal·sessionId면 세션에 기록된 활성 subscriptionId와 함께 서비스에 위임한다")
    void heartbeat_success_delegatesToServiceWithActiveSubscriptionId() {
        SimpMessageHeaderAccessor accessor = createAccessor(SESSION_ID, "sub-1");

        controller.heartbeat(CONTENT_ID, principalOf(WATCHER_ID), accessor);

        verify(watchingSessionService).heartbeat(WATCHER_ID, CONTENT_ID, SESSION_ID, "sub-1");
    }

    @Test
    @DisplayName("활성 subscriptionId가 세션에 기록되어 있지 않으면 null로 위임한다")
    void heartbeat_noActiveSubscription_delegatesWithNull() {
        SimpMessageHeaderAccessor accessor = createAccessor(SESSION_ID, null);

        controller.heartbeat(CONTENT_ID, principalOf(WATCHER_ID), accessor);

        verify(watchingSessionService).heartbeat(eq(WATCHER_ID), eq(CONTENT_ID), eq(SESSION_ID), isNull());
    }

    @Test
    @DisplayName("Principal이 없으면 서비스에 위임하지 않는다")
    void heartbeat_noPrincipal_doesNotDelegate() {
        SimpMessageHeaderAccessor accessor = createAccessor(SESSION_ID, "sub-1");

        controller.heartbeat(CONTENT_ID, null, accessor);

        verify(watchingSessionService, never()).heartbeat(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Principal 이름이 UUID 형식이 아니면 서비스에 위임하지 않는다")
    void heartbeat_nonUuidPrincipal_doesNotDelegate() {
        SimpMessageHeaderAccessor accessor = createAccessor(SESSION_ID, "sub-1");

        controller.heartbeat(CONTENT_ID, () -> "not-a-uuid", accessor);

        verify(watchingSessionService, never()).heartbeat(any(), any(), any(), any());
    }

    @Test
    @DisplayName("sessionId가 없으면(WebSocket sessionId 누락) 서비스에 위임하지 않는다")
    void heartbeat_noSessionId_doesNotDelegate() {
        SimpMessageHeaderAccessor accessor = createAccessor(null, "sub-1");

        controller.heartbeat(CONTENT_ID, principalOf(WATCHER_ID), accessor);

        verify(watchingSessionService, never()).heartbeat(any(), any(), any(), any());
    }
}
