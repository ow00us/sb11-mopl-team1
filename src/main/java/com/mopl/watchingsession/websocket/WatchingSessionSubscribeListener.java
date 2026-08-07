package com.mopl.watchingsession.websocket;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.security.websocket.StompErrorFrameSender;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * 클라이언트가 /sub/contents/{contentId}/watch 토픽을 SUBSCRIBE하는 시점을 "입장"으로 간주한다.
 * 클라이언트는 별도의 시작 메시지를 보낼 필요가 없고, 구독 자체가 입장 신호가 된다.
 *
 * STOMP UNSUBSCRIBE 프레임은 destination 없이 subscriptionId만 담아 오므로,
 * 여기서(SUBSCRIBE 시점) 세션 attribute에 subscriptionId -> contentId 매핑을 저장해두고
 * WatchingSessionUnsubscribeListener에서 그 매핑을 꺼내 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionSubscribeListener {

    // /sub/contents/{contentId}/watch 에서 contentId만 추출
    private static final Pattern WATCH_DESTINATION_PATTERN =
        Pattern.compile("^/sub/contents/([0-9a-fA-F-]{36})/watch$");
    private final WatchingSessionService watchingSessionService;
    private final WatchingSessionBroadcaster watchingSessionBroadcaster;
    private final StompErrorFrameSender errorFrameSender;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();

        UUID contentId = extractContentId(destination);
        if (contentId == null) {
            // 시청 토픽 구독이 아니면 이 리스너는 관여하지 않음
            return;
        }

        UUID watcherId = extractWatcherId(accessor.getUser());
        if (watcherId == null) {
            // CONNECT 시점에 이미 인증을 강제 -> Principal 없는 경우는 비정상
            // 로그만 남기고 종료
            log.warn("인증 정보 없이 토픽 구독 시도: destination={}", destination);
            return;
        }

        // sessionId Null 체크
        String sessionId = accessor.getSessionId();
        if (sessionId == null) {
            log.warn("WebSocket sessionId가 존재하지 않습니다. 구독 처리를 중단합니다: destination={}", destination);
            return;
        }

        // 퇴장 알림을 먼저 보내지 않고, 이전 세션 정보를 조회만 해 둠
        WatchingSessionDto prevSession = watchingSessionService.get(watcherId).orElse(null);

        // 유틸 내부에서 subscriptionId null 체크 및 맵 null 체크 후 매핑 시도
        boolean isMapped = WatchSubscriptionAttributes.put(accessor, contentId);

        // 매핑 실패 시 DB 로직 강행 차단
        if (!isMapped) {
            log.warn("구독 매핑 불가 상태(subscriptionId 누락 또는 세션 이상). DB 세션 생성을 시작하지 않음: contentId={}",
                contentId);
            return;
        }

        // DB 세션 시작
        WatchingSessionDto session;
        try {
            session = watchingSessionService.start(watcherId, contentId, sessionId);
        } catch (RuntimeException e) {
            // 예외 종류와 무관하게 인메모리 매핑 항상 정리
            WatchSubscriptionAttributes.remove(accessor);

            if (e instanceof BusinessException be) {
                log.warn("시청 세션 시작 실패, 구독 매핑 정리: watcherId={}, contentId={}, cause={}",
                    watcherId, contentId, e.getMessage());
                // 클라이언트에 실패 알림
                errorFrameSender.send(event.getMessage(), be.getClass().getSimpleName(), be.getErrorCode(),
                    be.getMessage(), be.getDetails());
                return;
            }

            // 예상 못한 예외는 삼키지 않고 그대로 전파
            log.error("시청 세션 시작 중 예상하지 못한 예외 발생, 구독 매핑만 정리 후 재전파: watcherId={}, contentId={}",
                watcherId, contentId, e);
            throw e;
        }

        // start()가 완벽하게 성공한 후에만 이전 세션에 대한 LEAVE 알림 전송
        if (prevSession != null && !prevSession.content().id().equals(contentId)) {
            watchingSessionBroadcaster.broadcastLeave(prevSession, prevSession.content().id());
        }

        watchingSessionBroadcaster.broadcastJoin(session, contentId);
    }

    private UUID extractContentId(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher matcher = WATCH_DESTINATION_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
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
