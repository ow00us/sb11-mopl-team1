package com.mopl.follow.repository;

import com.mopl.follow.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    Optional<Follow> findByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long countByFolloweeId(UUID followeeId);

    long countByFollowerId(UUID followerId);

    /**
     * 예외 없는 upsert. 이미 존재하는 관계면 rows affected 0, 신규 삽입이면 1.
     * <p>PostgreSQL 에서 유니크 제약 위반이 발생해 트랜잭션이 abort 되는 것을 방지하기 위해
     * try/catch 대신 {@code ON CONFLICT DO NOTHING} 로 처리한다.
     * <p>결과 UUID 는 반환하지 않고, 신규/기존 판단만 rows affected 로 수행한다.
     * 실제 엔티티가 필요하면 후속 {@code findByFollowerIdAndFolloweeId} 로 조회한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO follows (id, follower_id, followee_id, created_at, updated_at)
            VALUES (gen_random_uuid(),
                    CAST(:followerId AS uuid),
                    CAST(:followeeId AS uuid),
                    NOW(), NOW())
            ON CONFLICT ON CONSTRAINT uk_follows_follower_followee DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("followerId") String followerId,
                       @Param("followeeId") String followeeId);

    // nullable Instant/UUID 파라미터는 CAST(:param AS type) 문법 필수.
    // 정렬은 created_at DESC, id ASC (같은 시각 내 UUID 오름차순 타이브레이커).
    @Query(value = """
            SELECT * FROM follows
            WHERE followee_id = CAST(:followeeId AS uuid)
              AND (CAST(:cursorTime AS timestamptz) IS NULL
                   OR created_at < CAST(:cursorTime AS timestamptz)
                   OR (created_at = CAST(:cursorTime AS timestamptz)
                       AND id > CAST(:idAfter AS uuid)))
            ORDER BY created_at DESC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Follow> findFollowersByFolloweeIdDesc(
            @Param("followeeId") String followeeId,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    @Query(value = """
            SELECT * FROM follows
            WHERE follower_id = CAST(:followerId AS uuid)
              AND (CAST(:cursorTime AS timestamptz) IS NULL
                   OR created_at < CAST(:cursorTime AS timestamptz)
                   OR (created_at = CAST(:cursorTime AS timestamptz)
                       AND id > CAST(:idAfter AS uuid)))
            ORDER BY created_at DESC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Follow> findFollowingsByFollowerIdDesc(
            @Param("followerId") String followerId,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    /**
     * 친구의 친구(FoF) 기반 팔로우 추천.
     * <p>요청자가 팔로우한 사용자들이 팔로우하는 대상을 후보로 삼고, 공통 팔로잉 수(=서로 겹치는 매개자 수)를
     * 집계해 DESC 정렬한다. 요청자 본인과 이미 팔로우 중인 사용자는 제외한다.
     * <p>정렬은 {@code common_count DESC → user_id DESC} 로 안정성을 확보하고, 커서는
     * {@code (cursorCount, idAfter)} 짝을 lexicographic 하게 비교한다. HAVING 절에서 커서 조건을
     * 걸어 GROUP BY 이후에도 tie-break 이 유지되도록 한다.
     */
    @Query(value = """
            SELECT f2.followee_id AS "userId",
                   COUNT(*)       AS "commonCount"
            FROM follows f1
            JOIN follows f2 ON f2.follower_id = f1.followee_id
            WHERE f1.follower_id = CAST(:requesterId AS uuid)
              AND f2.followee_id <> CAST(:requesterId AS uuid)
              AND NOT EXISTS (
                    SELECT 1 FROM follows f3
                    WHERE f3.follower_id = CAST(:requesterId AS uuid)
                      AND f3.followee_id = f2.followee_id
              )
            GROUP BY f2.followee_id
            HAVING (CAST(:cursorCount AS bigint) IS NULL
                    OR COUNT(*) < CAST(:cursorCount AS bigint)
                    OR (COUNT(*) = CAST(:cursorCount AS bigint)
                        AND f2.followee_id < CAST(:idAfter AS uuid)))
            ORDER BY COUNT(*) DESC, f2.followee_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<FollowRecommendationRow> findRecommendations(
            @Param("requesterId") String requesterId,
            @Param("cursorCount") Long cursorCount,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );
}