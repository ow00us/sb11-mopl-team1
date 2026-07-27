package com.mopl.playlist.repository;

import com.mopl.playlist.entity.Playlist;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 플레이리스트 커서 페이지네이션 및 필터 카운트를 위한 저장소입니다. */
public interface PlaylistRepository extends JpaRepository<Playlist, UUID> {

    // ── updatedAt 정렬 ──────────────────────────────────────────────────────

    /** 커서 기반으로 updatedAt 오름차순 목록을 조회합니다. */
    @Query(value = """
            SELECT * FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
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
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    // ── subscribeCount 정렬 ─────────────────────────────────────────────────

    /** 커서 기반으로 subscribeCount 오름차순 목록을 조회합니다. */
    @Query(value = """
            SELECT * FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
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
            @Param("cursorCount") Long cursorCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    /** 필터 조건에 맞는 플레이리스트 수를 반환합니다. */
    @Query(value = """
            SELECT COUNT(*) FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
            """, nativeQuery = true)
    long countByFilter(
            @Param("keywordLike") String keywordLike,
            @Param("ownerIdEqual") String ownerIdEqual
    );

    /** 커서 기반으로 subscribeCount 내림차순 목록을 조회합니다. */
    @Query(value = """
            SELECT * FROM playlists
            WHERE  (CAST(:keywordLike AS text) IS NULL
                    OR LOWER(title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%'))
              AND  (CAST(:ownerIdEqual AS text) IS NULL
                    OR owner_id = CAST(:ownerIdEqual AS uuid))
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
            @Param("cursorCount") Long cursorCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );
}