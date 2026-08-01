package com.mopl.playlist.repository;

import com.mopl.playlist.entity.PlaylistSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PlaylistSubscriptionRepository extends JpaRepository<PlaylistSubscription, UUID> {

    Optional<PlaylistSubscription> findByPlaylistIdAndSubscriberId(UUID playlistId, UUID subscriberId);

    boolean existsByPlaylistIdAndSubscriberId(UUID playlistId, UUID subscriberId);

    long countByPlaylistId(UUID playlistId);

    @Query("SELECT ps.playlistId FROM PlaylistSubscription ps WHERE ps.subscriberId = :subscriberId AND ps.playlistId IN :playlistIds")
    Set<UUID> findSubscribedPlaylistIds(@Param("subscriberId") UUID subscriberId, @Param("playlistIds") List<UUID> playlistIds);

    // nullable Instant/UUID 는 CAST(:param AS type) 사용 (PostgreSQL 컨벤션).
    // 정렬은 created_at DESC, id ASC (같은 시각 UUID 오름차순 타이브레이커).
    @Query(value = """
            SELECT * FROM playlist_subscriptions
            WHERE playlist_id = CAST(:playlistId AS uuid)
              AND (CAST(:cursorTime AS timestamptz) IS NULL
                   OR created_at < CAST(:cursorTime AS timestamptz)
                   OR (created_at = CAST(:cursorTime AS timestamptz)
                       AND id > CAST(:idAfter AS uuid)))
            ORDER BY created_at DESC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<PlaylistSubscription> findByPlaylistIdDesc(
            @Param("playlistId") String playlistId,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );
}