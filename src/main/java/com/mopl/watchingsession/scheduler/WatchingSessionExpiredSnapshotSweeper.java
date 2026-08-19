package com.mopl.watchingsession.scheduler;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.presence.WatchingSessionPresenceWriter;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import com.mopl.watchingsession.service.WatchingSessionSnapshotWriter;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 시청 세션 스냅샷을 주기적으로 물리 삭제한다.
 *
 * 정상 종료(UNSUBSCRIBE/DISCONNECT)는 WatchingSessionService.end()/endByConnection()이 그때그때 DB 행을 지우는데
 * 이 스케줄러는 그 경로를 타지 못한 행(서버 프로세스 자체가 죽거나 재시작되어 DISCONNECT 이벤트가 발행되지 못한 경우)을 삭제한다.
 * Redis presence는 TTL로 스스로 사라지지만, DB 스냅샷은 누군가 지우기 전까지 expiresAt이 지난 뒤에도 행 자체는 남아있다.
 *
 * expiresAt이 지난 행은 조회 경로(countByContentId 등)에서 이미 필터링되어 시청자 집계에는 영향이 없다.
 * 이 스케줄러는 정합성이 아니라 스토리지가 무기한 쌓이지 않도록 정리하는 목적이라 배치 지연에 민감하지 않다.
 *
 * 이 경로로 지워지는 행에 대해서는 LEAVE를 브로드캐스트하지 않는다.
 * 서버가 죽어 DISCONNECT가 발행되지 못한 시점에 그 방을 실시간으로 보고 있던 다른 시청자의 연결도 대개 함께 끊겼을 것으로 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionExpiredSnapshotSweeper {

    private final WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;
    private final WatchingSessionPresenceWriter watchingSessionPresenceWriter;
    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    private static final int SWEEP_BATCH_SIZE = 500;

    @Scheduled(fixedDelayString = "#{@watchingSessionProperties.sweepInterval.toMillis()}")
    public void sweep() {
        Instant threshold = Instant.now();
        try {
            List<WatchingSessionSnapshot> candidates = watchingSessionSnapshotRepository.
                findExpiredCandidates(threshold, PageRequest.of(0, SWEEP_BATCH_SIZE));
            if (candidates.isEmpty()) {
                return;
            }

            Set<UUID> watcherIds = candidates.stream()
                .map(WatchingSessionSnapshot::getWatcherId)
                .collect(Collectors.toSet());
            Set<UUID> alivePresence = watchingSessionPresenceWriter.findExistingWatcherIds(watcherIds);

            List<UUID> orphanIds = candidates.stream()
                .filter(c -> !alivePresence.contains(c.getWatcherId()))
                .map(WatchingSessionSnapshot::getId)
                .toList();

            int deleted = orphanIds.isEmpty()
                ? 0
                :watchingSessionSnapshotWriter.deleteAllByIdInAndExpiresAtBefore(orphanIds, threshold);


            log.info("만료된 시청 세션 스냅샷 정리 완료: candidateCount={}, deletedCount={}, skippedAlive={}",
                candidates.size(), deleted, candidates.size() - orphanIds.size());
        } catch (RuntimeException e) {
            // 스윕 실패 자체가 스케줄러 자체를 죽이면 다음 주기까지 정리가 완전히 멈추므로 여기서 격리
            log.error("만료된 시청 세션 스냅샷 정리 실패", e);
        }
    }
}
