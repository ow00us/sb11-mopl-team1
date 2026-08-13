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

    // 동일 콘텐츠 재구독은 세션 연속 -> createdAt 유지
    // 다른 콘텐츠로 전환할 때만 createdAt 갱신되도록 delete+insert로 처리
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WatchingSessionSnapshot upsert(UUID watcherId, UUID contentId, Instant expiresAt) {
        return watchingSessionSnapshotRepository.findByWatcherId(watcherId)
            .map(existing -> replaceOrRefresh(existing, contentId, expiresAt))
            .orElseGet(() -> insertNew(watcherId, contentId, expiresAt));
    }

    private WatchingSessionSnapshot replaceOrRefresh(WatchingSessionSnapshot existing, UUID contentId, Instant expiresAt) {
        if (existing.getContentId().equals(contentId)) {
            existing.refresh(contentId, expiresAt);
            return watchingSessionSnapshotRepository.saveAndFlush(existing);
        }
        watchingSessionSnapshotRepository.delete(existing);
        watchingSessionSnapshotRepository.flush();
        return insertNew(existing.getWatcherId(), contentId, expiresAt);
    }

    private WatchingSessionSnapshot insertNew(UUID watcherId, UUID contentId, Instant expiresAt) {
        return watchingSessionSnapshotRepository.saveAndFlush(
            WatchingSessionSnapshot.builder()
                .watcherId(watcherId)
                .contentId(contentId)
                .expiresAt(expiresAt)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(UUID watcherId) {
        watchingSessionSnapshotRepository.deleteByWatcherId(watcherId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int renewExpiresAt(UUID watcherId, UUID contentId, Instant now, Instant newExpiresAt) {
        return watchingSessionSnapshotRepository.renewExpiresAt(watcherId, contentId, now, newExpiresAt);
    }

}
