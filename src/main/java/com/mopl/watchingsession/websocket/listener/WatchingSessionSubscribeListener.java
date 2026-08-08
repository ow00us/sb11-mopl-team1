package com.mopl.watchingsession.websocket.listener;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.websocket.StompErrorFrameSender;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import com.mopl.watchingsession.service.WatchingSessionService.ReplacedSession;
import com.mopl.watchingsession.service.WatchingSessionService.StartFailedException;
import com.mopl.watchingsession.websocket.stompsession.WatchSubscriptionAttributes;
import com.mopl.watchingsession.websocket.broadcast.WatchingSessionBroadcaster;
import java.security.Principal;
import java.util.Map;
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
 *
 * 소유권(활성 구독 판정) 자체는 WatchingSessionService.activeSessions가
 * (sessionId, subscriptionId) 쌍으로 전담한다. 이 리스너는 그 판정에 필요한
 * subscriptionId를 accessor에서 그대로 꺼내 start()에 넘기기만 하면 된다.
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

        String subscriptionId = accessor.getSubscriptionId();

        // 유틸 내부에서 subscriptionId null 체크 및 맵 null 체크 후 매핑 시도
        boolean isMapped = WatchSubscriptionAttributes.put(accessor, contentId);

        // 매핑 실패 시 DB 로직 강행 차단
        if (!isMapped) {
            log.warn("구독 매핑 불가 상태(subscriptionId 누락 또는 세션 이상). DB 세션 생성을 시작하지 않음: contentId={}",
                contentId);
            return;
        }

        // DB 세션 시작
        ReplacedSession replaced;
        try {
            replaced = watchingSessionService.start(watcherId, contentId, sessionId, subscriptionId);
        } catch (RuntimeException e) {
            // start() 실패 시 이 구독은 아직 활성으로 전환되지 않았으므로(activate() 호출 전),
            // consume()으로 지워도 이전 활성 구독(있었다면)에는 영향이 없다.
            WatchSubscriptionAttributes.consume(accessor);

            RuntimeException cause = e;
            if (e instanceof StartFailedException startFailed) {
                // upsert()까지는 성공해 소유권이 이미 새 콘텐츠로 넘어간 뒤 enrich()에서 실패
                // 보상 삭제가 실제로 수행됐을 때만(endedPrevious != null) 그 직전 콘텐츠의
                // 다른 시청자들에게 LEAVE 브로드캐스트
                WatchingSessionDto endedPrevious = startFailed.getEndedPrevious();
                if (endedPrevious != null) {
                    try {
                        watchingSessionBroadcaster.broadcastLeave(endedPrevious, endedPrevious.content().id());
                    } catch (RuntimeException broadcastFailure) {
                       log.error("보상 삭제 후 LEAVE 브로드캐스트 실패: watcherId={}, prevContentId={}",
                           watcherId, endedPrevious.content().id(), broadcastFailure);
                    }
                }
                // 클라이언트로 나가는 ERROR 프레임은 원래 실패 원인 기준 (기존 계약 유지)
                cause = (RuntimeException) startFailed.getCause();
            }

            if (cause instanceof BusinessException be) {
                log.warn("시청 세션 시작 실패, 구독 매핑 정리: watcherId={}, contentId={}, cause={}",
                    watcherId, contentId, be.getMessage());
                // 클라이언트에 실패 알림
                errorFrameSender.send(event.getMessage(), be.getClass().getSimpleName(), be.getErrorCode(),
                    be.getMessage(), be.getDetails());
                return;
            }

            // 인프라 오류 등 예상 못한 예외:
            // 브로커에는 이미 구독이 등록된 상태라 그대로 두면 유령 구독이 남는다.
            // 클라이언트에 INTERNAL_ERROR 프레임을 알리면 Spring 이 ERROR 프레임 전송
            // 이후 WebSocket 세션을 종료하고, SessionDisconnectEvent 가 발행되어
            // SimpleBrokerMessageHandler 가 해당 세션 구독을 자동 정리한다.
            log.error("시청 세션 시작 중 예상하지 못한 예외 발생, ERROR 프레임 발송: watcherId={}, contentId={}",
                watcherId, contentId, cause);
            errorFrameSender.send(event.getMessage(), cause.getClass().getSimpleName(),
                ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), Map.of());
            return;
        }

        // start()가 완벽하게 성공한 후에만 구독 활성화, 이전 세션에 대한 LEAVE 알림 전송
        WatchSubscriptionAttributes.activate(accessor);

        WatchingSessionDto session = replaced.session();
        WatchingSessionDto prevSession = replaced.previous();

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
