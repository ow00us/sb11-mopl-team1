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

    // ── 팔로워 목록 (특정 유저를 팔로우한 사람들) — createdAt DESC, id ASC 커서 페이지네이션 ──
    //
    // Red 단계 스텁: 실제 쿼리는 Green 에서 구현한다.
    // nullable Instant/UUID 파라미터는 CAST(:param AS type) 문법 필수 (ADR-B 컨벤션).
    @Query(value = "SELECT * FROM follows WHERE false", nativeQuery = true)
    List<Follow> findFollowersByFolloweeIdDesc(
            @Param("followeeId") String followeeId,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );

    // ── 팔로잉 목록 (특정 유저가 팔로우하는 사람들) — createdAt DESC, id ASC 커서 페이지네이션 ──
    @Query(value = "SELECT * FROM follows WHERE false", nativeQuery = true)
    List<Follow> findFollowingsByFollowerIdDesc(
            @Param("followerId") String followerId,
            @Param("cursorTime") Instant cursorTime,
            @Param("idAfter") String idAfter,
            @Param("limit") int limit
    );
}