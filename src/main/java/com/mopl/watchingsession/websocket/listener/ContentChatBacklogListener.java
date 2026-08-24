package com.mopl.watchingsession.websocket.listener;

import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.presence.ContentChatBuffer;
import com.mopl.watchingsession.websocket.broadcast.ContentChatBacklogSender;
import java.util.List;
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
 * 클라이언트가 /sub/contents/{contentId}/chat 토픽을 SUBSCRIBE하는 시점에,
 * 그 세션에만 최근 버퍼 메시지를 오래된 순서로 전달한다.
 *
 * watch 토픽 구독, DM 구독 등 다른 목적지의 SUBSCRIBE에는 관여하지 않는다.
 * ContentExistenceCache를 다시 확인하지 않는다 — WatchingSessionSubscribeExistenceInterceptor가
 * 이미 preSend 단계에서 존재하지 않는 콘텐츠의 SUBSCRIBE를 브로커에 도달하기 전에 차단하므로,
 * 이 리스너가 실행되는 시점의 SessionSubscribeEvent는 이미 그 검증을 통과한 것이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentChatBacklogListener {

    private static final Pattern CHAT_DESTINATION_PATTERN =
        Pattern.compile("^/sub/contents/([0-9a-fA-F-]{36})/chat$");

    private final ContentChatBuffer contentChatBuffer;
    private final ContentChatBacklogSender contentChatBacklogSender;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();

        UUID contentId = extractContentId(destination);
        if (contentId == null) {
            // 채팅 토픽 구독이 아니면 이 리스너는 관여하지 않음
            return;
        }

        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId == null || subscriptionId == null) {
            // 정상 STOMP 흐름에서는 SUBSCRIBE에 둘 다 항상 실린다. 없으면 이 프레임을
            // 어디로 되돌려야 할지 알 수 없으므로 로그만 남기고 조용히 넘어간다 —
            // 백로그는 부가 기능이라 여기서 구독 자체를 막을 이유가 없다.
            log.warn("sessionId 또는 subscriptionId 없이 채팅 토픽 구독 시도: destination={}", destination);
            return;
        }

        List<ContentChatDto> backlog = contentChatBuffer.recent(contentId);
        if (backlog.isEmpty()) {
            return;
        }

        contentChatBacklogSender.send(sessionId, subscriptionId, destination, backlog);
    }

    private UUID extractContentId(String destination) {
        if (destination == null) {
            return null;
        }
        Matcher matcher = CHAT_DESTINATION_PATTERN.matcher(destination);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
