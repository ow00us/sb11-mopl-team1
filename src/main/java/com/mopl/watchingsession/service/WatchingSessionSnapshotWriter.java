package com.mopl.watchingsession.service;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// upsert(있으면 갱신, 없으면 삽입)을 각각 독립 트랜잭션으로 실행하기 위한 클래스
@Component
@RequiredArgsConstructor
public class WatchingSessionSnapshotWriter {

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WatchingSessionSnapshot upsert(UUID watcherId, UUID contentId, Instant expiresAt) {
        return watchingSessionSnapshotRepository.findByWatcherId(watcherId)
            .map(existing -> {
                existing.refresh(contentId, expiresAt);
                return watchingSessionSnapshotRepository.saveAndFlush(existing);
            })
            .orElseGet(() -> watchingSessionSnapshotRepository.saveAndFlush(
                WatchingSessionSnapshot.builder()
                    .watcherId(watcherId)
                    .contentId(contentId)
                    .expiresAt(expiresAt)
                    .build()));
    }

}
