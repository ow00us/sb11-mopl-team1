package com.mopl.global.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByConsumerNameAndEventId(String consumerName, UUID eventId);

    /**
     * 처리 기록을 먼저 원자적으로 선점합니다.
     *
     * <p>같은 {@code (consumerName, eventId)} 를 두 트랜잭션이 동시에 INSERT 하면
     * PostgreSQL 이 먼저 처리 중인 트랜잭션의 완료를 기다립니다. 선행 트랜잭션이 커밋되면
     * 후행 INSERT 는 0을 반환하고, 롤백되면 후행 INSERT 가 성공합니다.
     */
    @Modifying
    @Query(value = """
        INSERT INTO processed_events (id, created_at, consumer_name, event_id, event_type)
        VALUES (:id, CURRENT_TIMESTAMP, :consumerName, :eventId, :eventType)
        ON CONFLICT (consumer_name, event_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
        @Param("id") UUID id,
        @Param("consumerName") String consumerName,
        @Param("eventId") UUID eventId,
        @Param("eventType") String eventType
    );

    /**
     * 보관 기간을 지난 처리 기록을 상한만큼 지웁니다.
     *
     * <p>지울 대상을 하위 조회로 먼저 고르고 {@code FOR UPDATE SKIP LOCKED} 로 잠급니다.
     * 여러 인스턴스가 동시에 실행해도 이미 잠긴 행은 기다리지 않고 건너뛰므로, 같은 행을 두
     * 번 지우려 하거나 서로 잠금을 기다리는 일이 없습니다.
     *
     * <p>상한을 두는 이유는 한 번의 실행이 오래 걸리면 그동안 잠금과 트랜잭션이 유지되어
     * 소비 경로의 기록 선점이 함께 느려지기 때문입니다. 남은 것은 다음 실행으로 넘깁니다.
     *
     * @return 지운 건수
     */
    @Modifying
    @Query(value = """
        DELETE FROM processed_events
        WHERE id IN (
          SELECT id FROM processed_events
          WHERE created_at <= :threshold
          ORDER BY created_at, id
          LIMIT :batchSize
          FOR UPDATE SKIP LOCKED
        )
        """, nativeQuery = true)
    int deleteRecordedBefore(
        @Param("threshold") Instant threshold, @Param("batchSize") int batchSize);
}
