package com.mopl.watchingsession.websocket.broadcast;

import com.mopl.watchingsession.dto.ChangeType;
import com.mopl.watchingsession.dto.WatchingSessionChange;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.presence.WatchingSessionPresenceReader;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import com.mopl.watchingsession.websocket.relay.contract.WatchingSessionRealtimeContract;
import com.mopl.watchingsession.websocket.relay.publisher.WatchingSessionRelayPublisher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionBroadcaster {

    // 시청자 수 조회가 실패했을 때 대신 내보내는 값
    // 정상적인 시청자 수는 항상 0 이상이므로 집계 실패했을 때 0을 내보내면 진짜 0명과 구분되지 않음.
    private static final long WATCHER_COUNT_UNAVAILABLE = -1L;

    private final SimpMessagingTemplate messagingTemplate;
    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;
    private final WatchingSessionPresenceReader presenceReader;
    private final WatchingSessionRelayPublisher watchingSessionRelayPublisher;

    public void broadcastJoin(WatchingSessionDto watchingSession, UUID contentId) {
        broadcastSafely(ChangeType.JOIN, watchingSession, contentId);
    }

    public void broadcastLeave(WatchingSessionDto watchingSession, UUID contentId) {
        broadcastSafely(ChangeType.LEAVE, watchingSession, contentId);
    }

    public void broadcast(UUID contentId, WatchingSessionChange change) {
        try {
            messagingTemplate.convertAndSend(WatchingSessionRealtimeContract.getDestination(contentId), change);
        } catch (RuntimeException e) {
            log.error("브로드캐스트 전송 실패: type={}, contentId={}, watcherId={}",
                change.type(), contentId, change.watchingSessionDto().watcher().userId(), e);
        }
    }

    // 브로드캐스트 실패를 호출자에게 전파하지 않음
    private void broadcastSafely(ChangeType type, WatchingSessionDto watchingSession, UUID contentId) {
        long watcherCount = safeCurrentWatcherCount(contentId);
        WatchingSessionChange change = (type == ChangeType.JOIN)
            ? WatchingSessionChange.join(watchingSession, watcherCount)
            : WatchingSessionChange.leave(watchingSession, watcherCount);

        broadcast(contentId, change);

        // 중계 발행 실패는 boolean으로만 알려지고 예외를 던지지 않으므로 이 인스턴스의 브로드캐스트는 계속 성공한다
        watchingSessionRelayPublisher.publish(contentId, change);
    }

    // 조회가 실패해도 대체 값을 반환해 브로드캐스트 자체는 계속 진행한다
    private long safeCurrentWatcherCount(UUID contentId) {
        try {
            return presenceReader.countByContent(contentId);
        } catch (RuntimeException e) {
            log.error("시청자 수 조회 실패, 대체 값 사용: contentId={}", contentId, e);
            return WATCHER_COUNT_UNAVAILABLE;
        }
    }
}
