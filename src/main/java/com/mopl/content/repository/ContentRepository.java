package com.mopl.content.repository;

import com.mopl.content.entity.Content;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 콘텐츠 커서 페이지네이션, 필터 카운트를 위한 저장소입니다. */
public interface ContentRepository extends JpaRepository<Content, UUID> {

    // ── createdAt 정렬 ──────────────────────────────────────────────────────

    @Query(value = """
            SELECT c.* FROM contents c
            LEFT JOIN content_tags ct
                   ON ct.content_id = c.id AND ct.tag IN (:tags)
            WHERE c.deleted_at IS NULL
              AND (CAST(:typeEqual AS text) IS NULL OR c.type = CAST(:typeEqual AS text))
              AND (CAST(:keywordLike AS text) IS NULL
                   OR LOWER(c.title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\'
                   OR LOWER(c.description) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\')
              AND (CAST(:cursorTime AS timestamptz) IS NULL
                   OR c.created_at > CAST(:cursorTime AS timestamptz)
                   OR (c.created_at = CAST(:cursorTime AS timestamptz) AND c.id > CAST(:idAfter AS uuid)))
            GROUP BY c.id
            HAVING (:tagCount = 0 OR COUNT(DISTINCT ct.tag) = :tagCount)
            ORDER BY c.created_at ASC, c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Content> findByCreatedAtAsc(
            @Param("typeEqual") String typeEqual,
            @Param("keywordLike") String keywordLike,
            @Param("tags") List<String> tags,
            @Param("tagCount") int tagCount,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT c.* FROM contents c
            LEFT JOIN content_tags ct
                   ON ct.content_id = c.id AND ct.tag IN (:tags)
            WHERE c.deleted_at IS NULL
              AND (CAST(:typeEqual AS text) IS NULL OR c.type = CAST(:typeEqual AS text))
              AND (CAST(:keywordLike AS text) IS NULL
                   OR LOWER(c.title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\'
                   OR LOWER(c.description) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\')
              AND (CAST(:cursorTime AS timestamptz) IS NULL
                   OR c.created_at < CAST(:cursorTime AS timestamptz)
                   OR (c.created_at = CAST(:cursorTime AS timestamptz) AND c.id > CAST(:idAfter AS uuid)))
            GROUP BY c.id
            HAVING (:tagCount = 0 OR COUNT(DISTINCT ct.tag) = :tagCount)
            ORDER BY c.created_at DESC, c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Content> findByCreatedAtDesc(
            @Param("typeEqual") String typeEqual,
            @Param("keywordLike") String keywordLike,
            @Param("tags") List<String> tags,
            @Param("tagCount") int tagCount,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    // ── watcherCount 정렬 ───────────────────────────────────────────────────

    @Query(value = """
            SELECT c.* FROM contents c
            LEFT JOIN content_tags ct
                   ON ct.content_id = c.id AND ct.tag IN (:tags)
            WHERE c.deleted_at IS NULL
              AND (CAST(:typeEqual AS text) IS NULL OR c.type = CAST(:typeEqual AS text))
              AND (CAST(:keywordLike AS text) IS NULL
                   OR LOWER(c.title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\'
                   OR LOWER(c.description) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\')
              AND (:cursorCount IS NULL
                   OR c.watcher_count > :cursorCount
                   OR (c.watcher_count = :cursorCount AND c.id > CAST(:idAfter AS uuid)))
            GROUP BY c.id
            HAVING (:tagCount = 0 OR COUNT(DISTINCT ct.tag) = :tagCount)
            ORDER BY c.watcher_count ASC, c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Content> findByWatcherCountAsc(
            @Param("typeEqual") String typeEqual,
            @Param("keywordLike") String keywordLike,
            @Param("tags") List<String> tags,
            @Param("tagCount") int tagCount,
            @Param("cursorCount") Long cursorCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT c.* FROM contents c
            LEFT JOIN content_tags ct
                   ON ct.content_id = c.id AND ct.tag IN (:tags)
            WHERE c.deleted_at IS NULL
              AND (CAST(:typeEqual AS text) IS NULL OR c.type = CAST(:typeEqual AS text))
              AND (CAST(:keywordLike AS text) IS NULL
                   OR LOWER(c.title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\'
                   OR LOWER(c.description) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\')
              AND (:cursorWatcherCount IS NULL
                   OR c.watcher_count < :cursorWatcherCount
                   OR (c.watcher_count = :cursorWatcherCount AND c.review_count < :cursorReviewCount)
                   OR (c.watcher_count = :cursorWatcherCount AND c.review_count = :cursorReviewCount
                       AND c.id > CAST(:idAfter AS uuid)))
            GROUP BY c.id
            HAVING (:tagCount = 0 OR COUNT(DISTINCT ct.tag) = :tagCount)
            ORDER BY c.watcher_count DESC, c.review_count DESC, c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Content> findByWatcherCountDesc(
            @Param("typeEqual") String typeEqual,
            @Param("keywordLike") String keywordLike,
            @Param("tags") List<String> tags,
            @Param("tagCount") int tagCount,
            @Param("cursorWatcherCount") Long cursorWatcherCount,
            @Param("cursorReviewCount") Long cursorReviewCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    // ── averageRating 정렬 ──────────────────────────────────────────────────

    @Query(value = """
            SELECT c.* FROM contents c
            LEFT JOIN content_tags ct
                   ON ct.content_id = c.id AND ct.tag IN (:tags)
            WHERE c.deleted_at IS NULL
              AND (CAST(:typeEqual AS text) IS NULL OR c.type = CAST(:typeEqual AS text))
              AND (CAST(:keywordLike AS text) IS NULL
                   OR LOWER(c.title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\'
                   OR LOWER(c.description) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\')
              AND (:cursorRating IS NULL
                   OR c.average_rating > :cursorRating
                   OR (c.average_rating = :cursorRating AND c.id > CAST(:idAfter AS uuid)))
            GROUP BY c.id
            HAVING (:tagCount = 0 OR COUNT(DISTINCT ct.tag) = :tagCount)
            ORDER BY c.average_rating ASC, c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Content> findByAverageRatingAsc(
            @Param("typeEqual") String typeEqual,
            @Param("keywordLike") String keywordLike,
            @Param("tags") List<String> tags,
            @Param("tagCount") int tagCount,
            @Param("cursorRating") BigDecimal cursorRating,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT c.* FROM contents c
            LEFT JOIN content_tags ct
                   ON ct.content_id = c.id AND ct.tag IN (:tags)
            WHERE c.deleted_at IS NULL
              AND (CAST(:typeEqual AS text) IS NULL OR c.type = CAST(:typeEqual AS text))
              AND (CAST(:keywordLike AS text) IS NULL
                   OR LOWER(c.title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\'
                   OR LOWER(c.description) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\')
              AND (:cursorRating IS NULL
                   OR c.average_rating < :cursorRating
                   OR (c.average_rating = :cursorRating AND c.id > CAST(:idAfter AS uuid)))
            GROUP BY c.id
            HAVING (:tagCount = 0 OR COUNT(DISTINCT ct.tag) = :tagCount)
            ORDER BY c.average_rating DESC, c.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Content> findByAverageRatingDesc(
            @Param("typeEqual") String typeEqual,
            @Param("keywordLike") String keywordLike,
            @Param("tags") List<String> tags,
            @Param("tagCount") int tagCount,
            @Param("cursorRating") BigDecimal cursorRating,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    // ── 카운트 ─────────────────────────────────────────────────────────────

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT c.id FROM contents c
                LEFT JOIN content_tags ct
                       ON ct.content_id = c.id AND ct.tag IN (:tags)
                WHERE c.deleted_at IS NULL
                  AND (CAST(:typeEqual AS text) IS NULL OR c.type = CAST(:typeEqual AS text))
                  AND (CAST(:keywordLike AS text) IS NULL
                       OR LOWER(c.title) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\'
                       OR LOWER(c.description) LIKE LOWER('%' || CAST(:keywordLike AS text) || '%') ESCAPE '\\')
                GROUP BY c.id
                HAVING (:tagCount = 0 OR COUNT(DISTINCT ct.tag) = :tagCount)
            ) matched
            """, nativeQuery = true)
    long countByFilter(
            @Param("typeEqual") String typeEqual,
            @Param("keywordLike") String keywordLike,
            @Param("tags") List<String> tags,
            @Param("tagCount") int tagCount
    );
}