package com.mopl.playlist.repository;

import com.mopl.playlist.entity.PlaylistSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 예외 없는 upsert. 이미 존재하는 구독이면 rows affected 0, 신규 삽입이면 1.
     * <p>PostgreSQL 에서 유니크 제약 위반이 발생해 트랜잭션이 abort 되는 것을 방지하기 위해
     * try/catch 대신 {@code ON CONFLICT DO NOTHING} 로 처리한다.
     * 카운터 증가는 rows affected == 1 인 신규 삽입 경로에서만 실행한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO playlist_subscriptions (id, playlist_id, subscriber_id, created_at, updated_at)
            VALUES (gen_random_uuid(),
                    CAST(:playlistId AS uuid),
                    CAST(:subscriberId AS uuid),
                    NOW(), NOW())
            ON CONFLICT ON CONSTRAINT uk_playlist_subscriptions_playlist_subscriber DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("playlistId") String playlistId,
                       @Param("subscriberId") String subscriberId);
}