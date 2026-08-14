package com.mopl.global.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Outbox 저장·조회입니다.
 *
 * <p>선점과 발행 완료 처리는 후속 이슈에서 추가합니다. 여기서는 기록과 상태 확인에
 * 필요한 조회만 둡니다.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    Optional<OutboxEvent> findByEventId(UUID eventId);

    boolean existsByEventId(UUID eventId);

    /**
     * 상태로 조회합니다. 상한을 반드시 받습니다.
     *
     * <p>{@code PUBLISHED} 는 계속 쌓이므로 상한 없는 조회를 두지 않습니다. 전체 규모는
     * {@link #countByStatus(OutboxStatus)} 로 확인합니다.
     */
    List<OutboxEvent> findByStatusOrderByOccurredAtAsc(OutboxStatus status, Limit limit);

    long countByStatus(OutboxStatus status);

    /**
     * 시도 시각이 지난 발행 대기 이벤트를 오래된 순으로 조회합니다.
     *
     * <p>정렬 기준이 {@code occurredAt} 이 아니라 {@code nextAttemptAt} 인 이유가 있습니다.
     * {@code idx_outbox_events_pending} 이 {@code (next_attempt_at, id)} 이므로 이 정렬은
     * 인덱스가 그대로 제공합니다. {@code occurredAt} 으로 정렬하면 인덱스가 필터에만 쓰이고
     * 매 조회마다 별도 정렬이 붙습니다.
     *
     * <p>재시도 대기 중인 건이 뒤로 밀리는 것도 이 정렬이 의도하는 동작입니다. 최초 기록은
     * {@code nextAttemptAt} 이 발생 시각과 같으므로 신규 건 사이의 순서는 발생 순과 같습니다.
     *
     * <p>같은 partition key 의 발행 순서는 이 조회가 보장하지 않습니다. 앞선 이벤트가 끝나기
     * 전에 뒤 이벤트를 발행하지 않는 규칙은 #230·#231 의 순서 검사가 담당하며,
     * {@code idx_outbox_events_partition_order} 를 씁니다.
     */
    List<OutboxEvent> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
        OutboxStatus status, Instant now, Limit limit);

    /**
     * 지금 선점할 수 있는 레코드의 id 를 batch 크기만큼 잠그고 가져옵니다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 를 씁니다. 다른 트랜잭션이 이미 잠근 행은 기다리지
     * 않고 건너뛰므로, 여러 relay 인스턴스가 동시에 실행해도 서로 다른 행을 가져갑니다.
     * 잠금 없이 조회하면 두 인스턴스가 같은 행을 읽고 둘 다 발행합니다.
     *
     * <p>선점 대상은 발행 대기 상태이고, 다음 시도 시각이 지났고, 소유자가 없거나 lease 가
     * 만료된 레코드입니다. lease 가 만료된 레코드를 포함하는 것이 relay 비정상 종료 회수
     * 경로입니다.
     *
     * <p>이 조회로 잠근 행은 호출 트랜잭션이 끝날 때까지 유지되므로, 이어지는
     * {@link #claimByIds(String, Instant, Instant, List)} 사이에 다른 인스턴스가 끼어들 수
     * 없습니다.
     */
    @Query(value = """
        SELECT id FROM outbox_events
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
     * <p>{@code updated_at} 을 직접 씁니다. 벌크 native UPDATE 는 JPA Auditing 을 거치지
     * 않아 그대로 두면 수정 시각이 낡은 값으로 남습니다.
     */
    @Modifying
    @Query(value = """
        UPDATE outbox_events
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

    List<OutboxEvent> findByIdInOrderByNextAttemptAtAscIdAsc(List<UUID> ids);
}
