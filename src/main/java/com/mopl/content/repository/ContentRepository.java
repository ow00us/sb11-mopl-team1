package com.mopl.content.repository;

import com.mopl.content.entity.Content;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 콘텐츠 커서 페이지네이션, 필터 카운트를 위한 저장소입니다. */
public interface ContentRepository extends JpaRepository<Content, UUID> {

    // ID 목록으로 콘텐츠와 태그를 함께 조회한다.
    // @ElementCollection 태그를 @EntityGraph로 미리 조인해 findAllById 사용 시 발생하는
    // 콘텐츠당 태그 배치 지연 로딩을 없앤다. 페이지 단위 콘텐츠 조회에 사용한다.
    @EntityGraph(attributePaths = {"tags"})
    List<Content> findAllWithTagsByIdIn(Collection<UUID> ids);

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

    // ── 리뷰 집계 원자적 갱신 ────────────────────────────────────────────────

    // 콘텐츠 행에 대한 배타적 락을 선점한다.
    //
    // PostgreSQL READ COMMITTED에서는 UPDATE가 행 잠금 대기 후 재개되더라도,
    // SET 절의 서브쿼리가 참조하는 "다른 테이블"(reviews)은 대기 시작 전 스냅샷을 그대로 사용한다
    // (EvalPlanQual은 갱신 대상 행 자체만 재조회할 뿐, 문 전체의 스냅샷을 새로 뜨지 않는다).
    // 따라서 락 선점과 집계 갱신을 하나의 UPDATE 문으로 합치면, 락 대기 후 재개된 트랜잭션이
    // 그 사이 커밋된 다른 리뷰들을 못 보고 집계를 계산해 값이 유실될 수 있다.
    // 락 선점을 별도 문으로 분리하면, 락 획득 후 실행되는 refreshReviewAggregate()는
    // 새 문(statement)으로서 그 시점까지 커밋된 최신 상태를 스냅샷으로 사용하므로 정확하다.

    // 콘텐츠 행 락 대기 시간을 제한한다. SET LOCAL이라 현재 트랜잭션에만 적용되고 커밋/롤백 시 자동 해제된다.
    // 이 값이 없으면 락을 쥔 트랜잭션이 끝나지 않을 때 요청 스레드와 DB 커넥션이 무한정 점유될 수 있다.
    // 반드시 findByIdForUpdate()보다 먼저, 같은 트랜잭션 안에서 호출해야 한다.
    @Modifying
    @Query(value = "SET LOCAL lock_timeout = '5s'", nativeQuery = true)
    void setLockTimeoutForReviewAggregateLock();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Content c WHERE c.id = :contentId")
    Optional<Content> findByIdForUpdate(@Param("contentId") UUID contentId);

    // 리뷰 반영 시 콘텐츠의 평균 평점·리뷰 수를 한 번의 UPDATE로 원자적으로 재계산한다.
    // 소프트 삭제된 콘텐츠(deleted_at IS NOT NULL)는 조건에 걸려 갱신되지 않는다.
    // findByIdForUpdate()로 콘텐츠 행 락을 먼저 선점한 뒤 별도 문으로 호출해야 한다.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE contents
            SET average_rating = ROUND(COALESCE(
                    (SELECT AVG(rating) FROM reviews WHERE content_id = CAST(:contentId AS uuid)), 0.0), 1),
                review_count = (SELECT COUNT(*) FROM reviews WHERE content_id = CAST(:contentId AS uuid))
            WHERE id = CAST(:contentId AS uuid) AND deleted_at IS NULL
            """, nativeQuery = true)
    void refreshReviewAggregate(@Param("contentId") UUID contentId);
}