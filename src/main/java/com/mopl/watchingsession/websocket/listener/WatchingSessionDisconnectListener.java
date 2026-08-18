package com.mopl.watchingsession.websocket.listener;

import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import com.mopl.watchingsession.websocket.broadcast.WatchingSessionBroadcaster;
import java.security.Principal;
import java.util.Optional;
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
 *
 * 소유권 판정은 sessionId만으로 한다(WatchingSessionService#endByConnection).
 * 연결이 끊기면 그 연결에 딸린 모든 구독이 함께 끊기므로, 이 연결에서 마지막에 활성이던
 * subscriptionId가 무엇이었는지는 판정에 필요하지 않다. 과거에는 세션 attribute에 기록해둔
 * activeSubscriptionId를 조회해 함께 비교했지만, activate()가 SUBSCRIBE 처리(start()) 성공
 * 후에야 호출되는 반면 presence는 start() 도중 이미 갱신되어 두 시점이 어긋날 수 있었다.
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

        // sessionId Null 체크
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            log.warn("WebSocket sessionId가 존재하지 않아 종료 처리를 중단합니다.");
            return;
        }

        // 삭제 대상과 브로드캐스트 대상이 반드시 같은 스냅샷이 되도록, 별도 get() 없이
        // endByConnection()이 반환하는 DTO(삭제를 확정한 그 snapshotId 기준)만 사용한다.
        Optional<WatchingSessionDto> ended = watchingSessionService.endByConnection(watcherId, sessionId);

        ended.ifPresent(session -> {
            try {
                watchingSessionBroadcaster.broadcastLeave(session, session.content().id());
            } catch (RuntimeException broadcastFailure) {
                log.error("DISCONNECT 처리 중 LEAVE 브로드캐스트 실패: watcherId={}", watcherId, broadcastFailure);
            }
        });
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
