package com.mopl.playlist.repository;

import com.mopl.playlist.entity.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 플레이리스트 커서 페이지네이션, 필터 카운트, 구독자 수 원자적 증감을 위한 저장소입니다. */
public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    // ── updatedAt 정렬 ──────────────────────────────────────────────────────

    @Query(value = """
            SELECT * FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
              AND  (CAST(:subscriberIdEqual AS text) IS NULL
                    OR id IN (SELECT playlist_id FROM playlist_subscriptions
                              WHERE subscriber_id = CAST(:subscriberIdEqual AS uuid)))
              AND  (CAST(:cursorTime AS timestamptz) IS NULL
                    OR updated_at > CAST(:cursorTime AS timestamptz)
                    OR (updated_at = CAST(:cursorTime AS timestamptz)
                        AND id > CAST(:idAfter AS uuid)))
            ORDER BY updated_at ASC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Playlist> findByUpdatedAtAsc(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("subscriberIdEqual") String subscriberIdEqual,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT * FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
              AND  (CAST(:subscriberIdEqual AS text) IS NULL
                    OR id IN (SELECT playlist_id FROM playlist_subscriptions
                              WHERE subscriber_id = CAST(:subscriberIdEqual AS uuid)))
              AND  (CAST(:cursorTime AS timestamptz) IS NULL
                    OR updated_at < CAST(:cursorTime AS timestamptz)
                    OR (updated_at = CAST(:cursorTime AS timestamptz)
                        AND id > CAST(:idAfter AS uuid)))
            ORDER BY updated_at DESC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Playlist> findByUpdatedAtDesc(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("subscriberIdEqual") String subscriberIdEqual,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    // ── subscribeCount 정렬 ─────────────────────────────────────────────────

    @Query(value = """
            SELECT * FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
              AND  (CAST(:subscriberIdEqual AS text) IS NULL
                    OR id IN (SELECT playlist_id FROM playlist_subscriptions
                              WHERE subscriber_id = CAST(:subscriberIdEqual AS uuid)))
              AND  (:cursorCount IS NULL
                    OR subscriber_count > :cursorCount
                    OR (subscriber_count = :cursorCount
                        AND id > CAST(:idAfter AS uuid)))
            ORDER BY subscriber_count ASC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Playlist> findBySubscriberCountAsc(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("subscriberIdEqual") String subscriberIdEqual,
            @Param("cursorCount") Long cursorCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT * FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
              AND  (CAST(:subscriberIdEqual AS text) IS NULL
                    OR id IN (SELECT playlist_id FROM playlist_subscriptions
                              WHERE subscriber_id = CAST(:subscriberIdEqual AS uuid)))
              AND  (:cursorCount IS NULL
                    OR subscriber_count < :cursorCount
                    OR (subscriber_count = :cursorCount
                        AND id > CAST(:idAfter AS uuid)))
            ORDER BY subscriber_count DESC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Playlist> findBySubscriberCountDesc(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("subscriberIdEqual") String subscriberIdEqual,
            @Param("cursorCount") Long cursorCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    // ── 카운트 ─────────────────────────────────────────────────────────────

    @Query(value = """
            SELECT COUNT(*) FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
              AND  (CAST(:subscriberIdEqual AS text) IS NULL
                    OR id IN (SELECT playlist_id FROM playlist_subscriptions
                              WHERE subscriber_id = CAST(:subscriberIdEqual AS uuid)))
            """, nativeQuery = true)
    long countByFilter(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual,
            @Param("subscriberIdEqual") String subscriberIdEqual
    );

    // ── 구독자 수 원자적 증감 ────────────────────────────────────────────────

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Playlist p SET p.subscriberCount = p.subscriberCount + 1 WHERE p.id = :id")
    void incrementSubscriberCount(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Playlist p SET p.subscriberCount = p.subscriberCount - 1 WHERE p.id = :id AND p.subscriberCount > 0")
    void decrementSubscriberCount(@Param("id") UUID id);
}