package com.mopl.watchingsession.repository;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchingSessionSnapshotRepository extends JpaRepository<WatchingSessionSnapshot, UUID> {

    // 사용자당 활성 세션 1개 -> 단건 조회, 삭제만 필요
    Optional<WatchingSessionSnapshot> findByWatcherId(UUID watcherId);

    void deleteByWatcherId(UUID watcherId);

}
