package com.mopl.watchingsession.websocket;

import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * WebSocket 연결 자체가 끊기는 경우(탭 닫기, 새로고침, 네트워크 끊김 등)를
 * "비정상 종료"로 간주해 자동 퇴장 처리한다.
 *
 * 유저당 활성 시청 세션은 항상 1개(watcher_id unique 제약)이므로,
 * 세션 정리(end 호출) 자체에는 contentId가 필요 없다.
 * 다만 브로드캐스트 대상 destination(/sub/contents/{contentId}/watch)을 정하려면
 * contentId가 필요하므로, end() 호출 전에 get()으로 세션 정보(watchingSession.content.id)를
 * 먼저 확보해둔다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionDisconnectListener {

    private final WatchingSessionService watchingSessionService;
    private final WatchingSessionBroadcaster watchingSessionBroadcaster;

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());

        UUID watcherId = extractWatcherId(accessor.getUser());
        if (watcherId == null) {
            // 인증 없이 연결이 성립된 적 없으므로 정상흐름에선 발생하지 않아야함
            // 방어적으로 로그만 남기고 종료
            log.warn("인증 정보 없이 연결 종료 이벤트 수신");
            return;
        }

        // end()가 DB에서 세션을 지우기 전에 브로드 캐스트에 필요한 세션 정보 확보
        WatchingSessionDto session = watchingSessionService.get(watcherId).orElse(null);

        // 이미 없는 걸 알고있으니 불필요한 DB 호출 없게 얼리 리턴으로 정리
        if (session == null) {
            return;
        }

        // watcherId 기준 활성 세션 삭제
        watchingSessionService.end(watcherId);
        watchingSessionBroadcaster.broadcastLeave(session, session.content().id());
    }

    private UUID extractWatcherId(Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
