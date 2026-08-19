package com.mopl.watchingsession.service;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// upsert(있으면 갱신, 없으면 삽입)을 각각 독립 트랜잭션으로 실행하기 위한 클래스
@Component
@RequiredArgsConstructor
public class WatchingSessionSnapshotWriter {

    // upsert가 새 행을 만들었는지(신규 삽입/콘텐츠 전환) 여부를 함께 반환. 실패 보상시 지워도 되는 행인지 판단용
    public record UpsertResult(WatchingSessionSnapshot snapshot, boolean isNewIdentity) {}

    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    // 동일 콘텐츠 재구독은 세션 연속 -> createdAt 유지
    // 다른 콘텐츠로 전환할 때만 createdAt 갱신되도록 delete+insert로 처리
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpsertResult upsert(UUID watcherId, UUID contentId, Instant expiresAt) {
        return watchingSessionSnapshotRepository.findByWatcherId(watcherId)
            .map(existing -> replaceOrRefresh(existing, contentId, expiresAt))
            .orElseGet(() -> new UpsertResult(insertNew(watcherId, contentId, expiresAt), true));
    }

    private UpsertResult replaceOrRefresh(WatchingSessionSnapshot existing, UUID contentId, Instant expiresAt) {
        if (existing.getContentId().equals(contentId)) {
            existing.refresh(contentId, expiresAt);
            return new UpsertResult(watchingSessionSnapshotRepository.saveAndFlush(existing), false);
        }
        watchingSessionSnapshotRepository.delete(existing);
        watchingSessionSnapshotRepository.flush();
        return new UpsertResult(insertNew(existing.getWatcherId(), contentId, expiresAt), true);
    }

    private WatchingSessionSnapshot insertNew(UUID watcherId, UUID contentId, Instant expiresAt) {
        return watchingSessionSnapshotRepository.saveAndFlush(
            WatchingSessionSnapshot.builder()
                .watcherId(watcherId)
                .contentId(contentId)
                .expiresAt(expiresAt)
                .build());
    }

    /**
     * watcherId만이 아니라 정확히 이 snapshotId와 일치하는 행만 삭제한다.
     *
     * blind delete(watcherId 기준)를 쓰면, 다른 인스턴스가 그 사이 같은 watcher에 대해
     * 새 세대의 행을 만들었을 때 그 새 행까지 함께 지워버릴 수 있다. presence가 확인해준
     * snapshotId로 조건을 좁혀야 이 레이스가 닫힌다.
     *
     * @return 실제로 삭제된 행 수 (0이면 이미 다른 세대로 교체된 뒤라는 뜻)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteById(UUID watcherId, UUID snapshotId, Instant expectedUpdatedAt) {
        return watchingSessionSnapshotRepository.deleteByWatcherIdAndId(watcherId, snapshotId, expectedUpdatedAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteAllByIdInAndExpiresAtBefore(Collection<UUID> ids, Instant before) {
        return watchingSessionSnapshotRepository.deleteAllByIdInAndExpiresAtBefore(ids, before);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int renewExpiresAt(UUID watcherId, UUID contentId, Instant newExpiresAt) {
        return watchingSessionSnapshotRepository.renewExpiresAt(watcherId, contentId, newExpiresAt);
    }

    /**
     * heartbeat 자가 복구 전용. watcherId 행 자체가 아예 없을 때만 새로 삽입한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<WatchingSessionSnapshot> insertIfAbsent(UUID watcherId, UUID contentId, Instant expiresAt) {
        if (watchingSessionSnapshotRepository.findByWatcherId(watcherId).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(insertNew(watcherId, contentId, expiresAt));
    }

}
