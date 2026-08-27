package com.mopl.watchingsession.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "watching_session_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_watching_session_snapshots_watcher_id", columnNames = "watcher_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WatchingSessionSnapshot extends BaseEntity {

    @Column(name = "watcher_id", nullable = false)
    private UUID watcherId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Builder
    public WatchingSessionSnapshot(UUID watcherId, UUID contentId, Instant expiresAt) {
        this.watcherId = watcherId;
        this.contentId = contentId;
        this.expiresAt = expiresAt;
    }

    // 스냅샷 내용 갱신 메서드
    public void refresh(UUID contentId, Instant expiresAt) {
        this.contentId = contentId;
        this.expiresAt = expiresAt;
    }

    // 지정 시각 기준으로 아직 활성인지 확인
    // 경계 규약: expiresAt과 정확히 같은 시각은 비활성으로 본다.
    // 이 부등호는 조회 쿼리의 `s.expiresAt > :now`와 반드시 같은 판정이어야 하므로 한쪽만 바꾸면 안 된다.
    public boolean isActiveAt(Instant now) {
        return now.isBefore(this.expiresAt);
    }
}
