package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 최종 실패 이벤트의 조회와 수동 재처리를 검증합니다.
 *
 * <p>{@code @Transactional} 을 클래스에 붙이지 않습니다. 서비스가 자기 트랜잭션에서 커밋한
 * 결과를 다시 읽어야 상태 전환이 실제로 반영됐는지 확인됩니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OutboxFailureServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final Instant NOW = Instant.parse("2026-08-15T03:00:00Z");

    @Autowired
    OutboxFailureService outboxFailureService;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        outboxEventRepository.deleteAll();
    }

    private OutboxEvent save(Instant occurredAt) {
        UUID aggregateId = UUID.randomUUID();
        return outboxEventRepository.saveAndFlush(new OutboxEvent(
            UUID.randomUUID(), "follow.created", 1, aggregateId, occurredAt,
            "{\"followerId\":\"a\"}", aggregateId.toString(), "AGGREGATE", occurredAt));
    }

    /** 최종 실패는 relay 가 만드는 상태입니다. 여기서는 relay 없이 상태만 맞춥니다. */
    private OutboxEvent saveFailed(Instant occurredAt) {
        OutboxEvent saved = save(occurredAt);
        jdbcTemplate.update("""
            UPDATE outbox_events
            SET status = 'FAILED', attempts = 3, last_error = '발행 실패'
            WHERE id = ?
            """, saved.getId());
        return saved;
    }

    @Test
    @DisplayName("최종 실패한 이벤트를 발생 순으로 조회한다")
    void findFailed_returnsFailedInOccurredOrder() {
        OutboxEvent older = saveFailed(NOW.minus(2, ChronoUnit.HOURS));
        OutboxEvent newer = saveFailed(NOW.minus(1, ChronoUnit.HOURS));
        save(NOW);

        List<OutboxEvent> failed = outboxFailureService.findFailed(10);

        assertThat(failed).extracting(OutboxEvent::getId)
            .containsExactly(older.getId(), newer.getId());
        assertThat(outboxFailureService.countFailed()).isEqualTo(2);
    }

    @Test
    @DisplayName("조회 상한이 1보다 작으면 거부한다")
    void findFailed_rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> outboxFailureService.findFailed(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("최종 실패 이벤트를 다시 발행 대기로 돌린다")
    void requeue_movesFailedBackToPending() {
        OutboxEvent saved = saveFailed(NOW.minus(1, ChronoUnit.HOURS));
        // jsonb 는 키 순서와 공백을 정규화하므로 저장 직전 문자열이 아니라 읽어온 값과
        // 비교해야 보존 여부를 확인할 수 있습니다.
        String payloadBefore = outboxEventRepository.findById(saved.getId()).orElseThrow().getPayload();

        assertThat(outboxFailureService.requeue(saved.getEventId(), NOW)).isTrue();

        OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getNextAttemptAt()).isEqualTo(NOW);
        // 이벤트 식별자와 파티션 키는 그대로여야 소비자 멱등 판정과 순서가 유지됩니다.
        assertThat(after.getEventId()).isEqualTo(saved.getEventId());
        assertThat(after.getPartitionKey()).isEqualTo(saved.getPartitionKey());
        assertThat(after.getPayload()).isEqualTo(payloadBefore);
        // 직전 실패 원인은 남겨 같은 실패가 반복되는지 확인할 수 있게 합니다.
        assertThat(after.getLastError()).isEqualTo("발행 실패");
    }

    @Test
    @DisplayName("최종 실패 상태가 아닌 이벤트는 되돌리지 않는다")
    void requeue_ignoresNonFailedEvent() {
        OutboxEvent pending = save(NOW);

        assertThat(outboxFailureService.requeue(pending.getEventId(), NOW)).isFalse();
        assertThat(outboxEventRepository.findById(pending.getId()).orElseThrow().getStatus())
            .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("없는 eventId는 되돌리지 않는다")
    void requeue_ignoresUnknownEventId() {
        assertThat(outboxFailureService.requeue(UUID.randomUUID(), NOW)).isFalse();
    }

    /**
     * 브로커 장애처럼 원인이 하나여서 여러 건이 함께 실패한 상황입니다.
     *
     * <p>상한을 받는 이유는 한 번에 되돌린 양이 곧 다음 relay 주기의 부하가 되기 때문입니다.
     */
    @Test
    @DisplayName("최종 실패 이벤트를 상한만큼 한꺼번에 되돌린다")
    void requeueAll_movesUpToLimit() {
        saveFailed(NOW.minus(3, ChronoUnit.HOURS));
        saveFailed(NOW.minus(2, ChronoUnit.HOURS));
        saveFailed(NOW.minus(1, ChronoUnit.HOURS));

        assertThat(outboxFailureService.requeueAll(2, NOW)).isEqualTo(2);

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.FAILED)).isEqualTo(1);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);
    }
}
