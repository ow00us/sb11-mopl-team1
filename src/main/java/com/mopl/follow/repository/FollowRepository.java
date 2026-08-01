package com.mopl.follow.repository;

import com.mopl.follow.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {

    Optional<Follow> findByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    boolean existsByFollowerIdAndFolloweeId(UUID followerId, UUID followeeId);

    long countByFolloweeId(UUID followeeId);

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
}