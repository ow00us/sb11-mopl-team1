package com.mopl.watchingsession.controller;

import com.mopl.watchingsession.service.WatchingSessionService;
import com.mopl.watchingsession.websocket.stompsession.WatchSubscriptionAttributes;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * 시청 중임을 알리는 애플리케이션 레벨 heartbeat를 처리한다.
 *
 * WebSocketConfig의 브로커 heartbeat(4초)는 전송 계층 keepalive이고
 * SimpleBrokerMessageHandler 내부에서만 처리되어 "어떤 watcher가 아직 보고 있는가"를
 * 알 수 없다. 그래서 애플리케이션 목적지를 따로 둔다.
 *
 * SEND 프레임에는 subscriptionId 헤더가 없으므로, 이 연결에서 마지막으로 활성화된
 * 구독 ID를 세션 attribute에서 꺼내 소유권 판정에 넘긴다.
 *
 * 실패는 ERROR 프레임을 만들지 않는다. @MessageMapping에서 예외가 나가면
 * StompMessagingControllerAdvice가 ERROR 프레임을 보내고 세션이 종료되는데,
 * 주기적 배경 신호 한 번의 실패로 정상 시청 연결을 끊는 것은 과잉이다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WatchingSessionHeartbeatController {

    private final WatchingSessionService watchingSessionService;

    @MessageMapping("/contents/{contentId}/watch/heartbeat")
    public void heartbeat(
        @DestinationVariable UUID contentId,
        Principal principal,
        SimpMessageHeaderAccessor accessor
    ) {
        UUID watcherId = extractWatcherId(principal);
        if (watcherId == null) {
            // CONNECT 인터셉터가 인증을 강제하므로 정상 흐름에선 발생하지 않음
            log.warn("인증 정보 없이 heartbeat 수신: contentId={}", contentId);
            return;
        }

        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            log.warn("Websocket sessionId가 없어 heartbeat를 무시합니다: watcherId={}", watcherId);
            return;
        }

        String subscriptionId = WatchSubscriptionAttributes.currentActiveSubscriptionId(accessor);

        watchingSessionService.heartbeat(watcherId, contentId, sessionId, subscriptionId);
    }

    private UUID extractWatcherId(Principal principal) {
        if (principal == null || principal.getName() == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
