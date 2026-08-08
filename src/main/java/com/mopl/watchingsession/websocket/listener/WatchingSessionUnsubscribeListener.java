package com.mopl.watchingsession.websocket.listener;

import com.mopl.watchingsession.dto.SubscriptionConsumeResult;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import com.mopl.watchingsession.websocket.stompsession.WatchSubscriptionAttributes;
import com.mopl.watchingsession.websocket.broadcast.WatchingSessionBroadcaster;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * 클라이언트가 /sub/contents/{contentId}/watch 토픽을 명시적으로 UNSUBSCRIBE하는 시점을
 * "정상 퇴장"으로 간주한다. (연결은 유지된 채 구독만 해제하는 경우)
 * 연결 자체가 끊기는 비정상 종료는 SessionDisconnectEvent 리스너에서 별도 처리한다.
 *
 * UNSUBSCRIBE 프레임에는 destination이 없고 subscriptionId만 있으므로,
 * 입장 시점(WatchingSessionSubscribeListener)에서 세션 attribute에 저장해둔
 * subscriptionId -> contentId 매핑(WatchSubscriptionAttributes)을 여기서 조회해 복원한다.
 *
 * 낡은 구독(같은 연결에서 재구독으로 대체된 구독)인지 여부는 더 이상 이 리스너나
 * WatchSubscriptionAttributes가 판정하지 않는다. WatchingSessionService.end()가
 * (sessionId, subscriptionId) 쌍을 소유권과 비교해 원자적으로 판정하므로,
 * 이 리스너는 accessor에서 얻은 값을 그대로 넘기기만 하면 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionUnsubscribeListener {

    private final WatchingSessionService watchingSessionService;
    private final WatchingSessionBroadcaster watchingSessionBroadcaster;

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String subscriptionId = accessor.getSubscriptionId();

        SubscriptionConsumeResult result = WatchSubscriptionAttributes.consume(accessor);

        if (!result.hasMapping()) {
            // 시청 토픽 구독 해제가 아니었음
            return;
        }

        UUID contentId = result.contentId();

        UUID watcherId = extractWatcherId(accessor.getUser());
        if (watcherId == null) {
            log.warn("인증 정보 없이 시청 토픽 구독 해제 처리 시도: contentId={}", contentId);
            return;
        }

        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            log.warn("WebSocket sessionId가 존재하지 않아 구독 해제 처리를 중단합니다.");
            return;
        }

        // end()가 DB에서 세션을 지우기 전에 브로드캐스트에 필요한 세션정보 먼저 확보
        WatchingSessionDto session = watchingSessionService.get(watcherId).orElse(null);

        // 현재 활성화된 세션이 없거나, 해제하려는 구독의 contentId와 다르면 무시
        // 매핑에 저장된 contentId와 db에서 조회한 활성 세션의 콘텐츠가 다른 케이스 방어
        // 어느 destination으로 보내도 payload와 대상이 어긋나므로 브로드캐스트 생략
        if (session == null || !contentId.equals(session.content().id())) {
            return;
        }

        // watcherId 기준 활성 세션 삭제 및 브로드캐스트. sessionId가 일치할 때만
        // 다른 연결로 이미 소유권이 넘어갔다면 이 UNSUBSCRIBE는 오래된 연결의 정리 시도이므로 삭제 안함
        boolean actuallyDeleted = watchingSessionService.end(watcherId, sessionId, subscriptionId);
        if (actuallyDeleted) {
            watchingSessionBroadcaster.broadcastLeave(session, contentId);
        }
    }

    private UUID extractWatcherId(Principal principal) {
        if (principal == null) return null;
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
