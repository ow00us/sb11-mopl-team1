package com.mopl.follow.repository;

import com.mopl.follow.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
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
}