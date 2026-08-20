package com.mopl.content.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 콘텐츠 검색 재시도 대기열 저장·조회입니다. outbox_events의 claim 쿼리와 같은 구조입니다.
 */
public interface ContentSearchRetryRepository extends JpaRepository<ContentSearchRetry, UUID> {

    /**
     * 지금 선점할 수 있는 레코드의 id를 batch 크기만큼 잠그고 가져옵니다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}로 다른 인스턴스가 이미 잠근 행은 기다리지 않고
     * 건너뜁니다. 잠금 없이 조회하면 두 인스턴스가 같은 행을 읽고 둘 다 재시도합니다.
     */
    @Query(value = """
        SELECT id FROM content_search_retries
        WHERE status = 'PENDING'
          AND next_attempt_at <= :now
          AND (claim_owner IS NULL OR claim_expires_at <= :now)
        ORDER BY next_attempt_at, id
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<UUID> findClaimableIds(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /**
     * 선점 소유자와 lease 만료 시각을 기록합니다.
     *
     * <p>벌크 native UPDATE는 JPA Auditing을 거치지 않으므로 updated_at을 직접 씁니다.
     */
    @Modifying
    @Query(value = """
        UPDATE content_search_retries
        SET claim_owner = :owner,
            claim_expires_at = :leaseExpiresAt,
            updated_at = :now
        WHERE id IN (:ids)
        """, nativeQuery = true)
    int claimByIds(
        @Param("owner") String owner,
        @Param("leaseExpiresAt") Instant leaseExpiresAt,
        @Param("now") Instant now,
        @Param("ids") List<UUID> ids
    );

    List<ContentSearchRetry> findByIdInOrderByNextAttemptAtAscIdAsc(List<UUID> ids);
}
