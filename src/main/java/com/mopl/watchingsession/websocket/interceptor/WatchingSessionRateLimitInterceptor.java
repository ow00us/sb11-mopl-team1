package com.mopl.watchingsession.websocket.interceptor;

import com.mopl.watchingsession.config.WatchingSessionProperties;
import com.mopl.watchingsession.websocket.stompsession.RateLimitAttributes;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * heartbeat/chat SEND의 최소 전송 간격과 watch 재구독의 최소 간격, chat 구독 개수 상한을
 * preSend()에서 적용한다. 초과분은 이후 처리기(리스너, 브로커 등록)에 전혀 도달하지 않고 null을 반환해 조용히 드롭된다
 *
 * 세션 attribute를 얻을 수 없는 프레임은 판정 불가이므로 통과시킨다(fail-open).
 * 이 인터셉터는 인가 게이트가 아니라 과부하·어뷰징 방어용이다.
 *
 * 체인 위치는 StompDestinationAuthorizationInterceptor 직후, 그리고
 * WatchingSessionSubscribeExistenceInterceptor 이전이어야 한다. 그래야 제한에 걸린
 * SUBSCRIBE가 콘텐츠 존재 여부 DB 조회 자체를 발생시키지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionRateLimitInterceptor implements ChannelInterceptor {

    private static final String UUID_PATTERN =
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private static final Pattern HEARTBEAT_SEND =
        Pattern.compile("^/pub/contents/" + UUID_PATTERN + "/watch/heartbeat$");
    private static final Pattern CHAT_SEND =
        Pattern.compile("^/pub/contents/" + UUID_PATTERN + "/chat$");
    private static final Pattern WATCH_SUBSCRIBE =
        Pattern.compile("^/sub/contents/" + UUID_PATTERN + "/watch$");
    private static final Pattern CHAT_SUBSCRIBE =
        Pattern.compile("^/sub/contents/" + UUID_PATTERN + "/chat$");

    private final WatchingSessionProperties watchingSessionProperties;
    private final WatchingSessionRateLimitMetrics metrics;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        String destination = accessor.getDestination();

        if (command == StompCommand.SEND && destination != null) {
            if (HEARTBEAT_SEND.matcher(destination).matches()) {
                long minIntervalMillis = Math.max(1L, watchingSessionProperties.getHeartbeatInterval().toMillis() / 2);
                return applyMinInterval(message, accessor,
                    RateLimitAttributes::tryConsumeHeartbeatSend, minIntervalMillis,
                    metrics::recordHeartbeatDropped, destination);
            }
            if (CHAT_SEND.matcher(destination).matches()) {
                long minIntervalMillis = watchingSessionProperties.getChatSendMinInterval().toMillis();
                return applyMinInterval(message, accessor,
                    RateLimitAttributes::tryConsumeChatSend, minIntervalMillis,
                    metrics::recordChatSendDropped, destination);
            }
            return message;
        }
        if (command == StompCommand.SUBSCRIBE && destination != null) {
            if (WATCH_SUBSCRIBE.matcher(destination).matches()) {
                long minIntervalMillis = watchingSessionProperties.getWatchSubscribeMinInterval().toMillis();
                return applyMinInterval(message, accessor,
                    RateLimitAttributes::tryConsumeWatchSubscribe, minIntervalMillis,
                    metrics::recordWatchSubscribeDropped, destination);
            }
            if (CHAT_SUBSCRIBE.matcher(destination).matches()) {
                int limit = watchingSessionProperties.getChatSubscriptionLimit();
                if (RateLimitAttributes.tryAcquireChatSubscription(accessor, limit)) {
                    return message;
                }
                metrics.recordChatSubscribeDropped();
                log.debug("chat 구독 개수 상한 초과로 무시: destination={}", destination);
                return null;
            }
            return message;
        }

        if (command == StompCommand.UNSUBSCRIBE) {
            // destination이 없으므로 목적지 종류를 가리지 않고 항상 해제 시도한다.
            // chat 구독이 아니었던 subscriptionId에 대한 호출은 Set.remove가 멱등이라 안전하다.
            RateLimitAttributes.releaseChatSubscription(accessor);
        }

        return message;
    }

    @Override
    public void afterSendCompletion(
        Message<?> message, MessageChannel channel, boolean sent, Exception ex
    ) {
        if (sent) {
            return;
        }
        // preSend 체인 뒤쪽(SubscribeExistenceInterceptor 등)이 null을 반환해 최종적으로
        // 전송되지 않은 경우. 이 인터셉터가 먼저 예약해둔 chat 구독 자리를 되돌리지 않으면
        // 존재하지 않는 콘텐츠에 반복 구독 시도만으로 상한이 소진되어, 이후 유효한 채팅 구독까지 차단당한다.
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return;
        }
        String destination = accessor.getDestination();
        if (destination != null && CHAT_SUBSCRIBE.matcher(destination).matches()) {
            RateLimitAttributes.releaseChatSubscription(accessor);
        }
    }

    @FunctionalInterface
    private interface MinIntervalCheck {
        boolean tryConsume(StompHeaderAccessor accessor, long minIntervalMillis);
    }

    private Message<?> applyMinInterval(
        Message<?> message,
        StompHeaderAccessor accessor,
        MinIntervalCheck check,
        long minIntervalMillis,
        Runnable onDropped,
        String destination
    ) {
        if (check.tryConsume(accessor, minIntervalMillis)) {
            return message;
        }
        onDropped.run();
        log.debug("최소 간격 미달로 무시: destination={}", destination);
        return null;
    }
}
