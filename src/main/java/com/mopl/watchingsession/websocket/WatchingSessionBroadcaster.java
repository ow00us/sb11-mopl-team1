package com.mopl.watchingsession.websocket;

import com.mopl.watchingsession.dto.WatchingSessionChange;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WatchingSessionBroadcaster {

    private static final String DESTINATION_TEMPLATE = "/sub/contents/%s/watch";

    private final SimpMessagingTemplate messagingTemplate;
    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    public void broadcastJoin(WatchingSessionDto watchingSession, UUID contentId) {
        broadcast(contentId, WatchingSessionChange.join(watchingSession, currentWatcherCount(contentId)));
    }

    public void broadcastLeave(WatchingSessionDto watchingSession, UUID contentId) {
        broadcast(contentId, WatchingSessionChange.leave(watchingSession, currentWatcherCount(contentId)));
    }

    private void broadcast(UUID contentId, WatchingSessionChange change) {
        messagingTemplate.convertAndSend(DESTINATION_TEMPLATE.formatted(contentId), change);
    }

    private long currentWatcherCount(UUID contentId) {
        return watchingSessionSnapshotRepository.countByContentId(contentId, null, Instant.now());
    }
}
