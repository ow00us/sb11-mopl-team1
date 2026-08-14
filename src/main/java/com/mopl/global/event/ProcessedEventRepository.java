package com.mopl.global.event;

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
}
