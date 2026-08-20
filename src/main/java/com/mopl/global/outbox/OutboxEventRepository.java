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

    /** 선점 중인 레코드 수입니다. lease 가 만료됐지만 아직 회수되지 않은 것도 포함합니다. */
    long countByClaimOwnerIsNotNull();

    /**
     * 해당 상태에서 가장 오래된 발생 시각입니다. 대상이 없으면 {@code null} 입니다.
     *
     * <p>발행 지연을 재는 기준이 기록 시각이 아니라 발생 시각인 이유가 있습니다. 도메인 사실이
     * 언제 확정됐는지부터가 소비자가 기다린 시간입니다.
     */
    @Query("SELECT MIN(e.occurredAt) FROM OutboxEvent e WHERE e.status = :status")
    Instant findOldestOccurredAt(@Param("status") OutboxStatus status);

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
     * <p>이 조회는 순서 게이트를 적용하지 않습니다. 상태와 시도 시각만 봅니다. 같은 partition
     * key 안의 순서는 선점 조회인 {@link #findClaimableIds(Instant, int)} 가 다룹니다.
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
     *
     * <h2>순서 게이트</h2>
     *
     * <p>계약 §9 는 순서가 필요한 이벤트를 같은 partition key 안에서 발생 순으로 발행하도록
     * 정합니다. 그래서 같은 키에 아직 끝나지 않은 앞선 이벤트가 있으면 선점하지 않습니다.
     * 이 조건이 없으면 앞선 이벤트가 실패해 재시도 대기로 밀릴 때 뒤 이벤트가 먼저 나갑니다.
     *
     * <ul>
     *   <li>{@code orderingScope} 가 {@code NONE} 인 이벤트에는 적용하지 않습니다. 계약이
     *       선후 관계가 없다고 선언한 이벤트입니다.</li>
     *   <li>막는 쪽도 {@code NONE} 은 제외합니다. 순서를 선언하지 않은 이벤트가 같은 키를 쓴다는
     *       이유로 다른 이벤트를 세울 수는 없습니다.</li>
     *   <li>{@code PENDING} 과 {@code FAILED} 가 막습니다. {@code PENDING} 은 아직 나가지 않은
     *       것이고, {@code FAILED} 는 계약이 후속을 계속 차단하도록 정한 상태입니다. 사람이
     *       재처리하거나 {@code EXPIRED} 로 넘겨야 뒤 이벤트가 진행합니다.</li>
     *   <li>{@code PUBLISHED} 와 {@code EXPIRED} 는 통과시킵니다.</li>
     *   <li>선후 비교는 {@code (occurred_at, id)} 로 합니다. 같은 시각이면 id 가 가릅니다.</li>
     * </ul>
     *
     * <p>{@code FOR UPDATE} 에 대상 테이블을 명시합니다. 지정하지 않으면 게이트 확인용으로 읽는
     * 앞선 행까지 잠글 수 있는데, 그 행은 다른 인스턴스가 발행 중일 수 있습니다.
     *
     * <p>게이트 확인은 {@code idx_outbox_events_partition_gate} 를 씁니다. 상태를 가리지 않는
     * 인덱스면 발행을 마친 행까지 훑어, 한 키에 이벤트가 쌓일수록 확인 비용이 커집니다.
     */
    @Query(value = """
        SELECT e.id FROM outbox_events e
        WHERE e.status = 'PENDING'
          AND e.next_attempt_at <= :now
          AND (e.claim_owner IS NULL OR e.claim_expires_at <= :now)
          AND (
            e.ordering_scope = 'NONE'
            OR NOT EXISTS (
              SELECT 1 FROM outbox_events earlier
              WHERE earlier.partition_key = e.partition_key
                AND earlier.ordering_scope <> 'NONE'
                AND earlier.status IN ('PENDING', 'FAILED')
                AND (earlier.occurred_at, earlier.id) < (e.occurred_at, e.id)
            )
          )
        ORDER BY e.next_attempt_at, e.id
        LIMIT :batchSize
        FOR UPDATE OF e SKIP LOCKED
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
