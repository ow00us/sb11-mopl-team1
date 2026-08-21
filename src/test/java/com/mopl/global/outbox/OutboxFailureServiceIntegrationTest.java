package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 최종 실패 이벤트의 조회와 수동 재처리, 건너뛰기 종결을 검증합니다.
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
    private static final UUID ACTOR_ID =
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ACTOR_ID =
        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

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
            "{\"followerId\":\"a\"}", aggregateId.toString(), "AGGREGATE",
            "follow.created:" + aggregateId, occurredAt));
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

        assertThat(outboxFailureService.requeue(saved.getEventId(), NOW))
            .isEqualTo(OutboxRequeueOutcome.REQUEUED);

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

        assertThat(outboxFailureService.requeue(pending.getEventId(), NOW))
            .isEqualTo(OutboxRequeueOutcome.NOT_FAILED);
        assertThat(outboxEventRepository.findById(pending.getId()).orElseThrow().getStatus())
            .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("없는 eventId는 되돌리지 않는다")
    void requeue_ignoresUnknownEventId() {
        assertThat(outboxFailureService.requeue(UUID.randomUUID(), NOW))
            .isEqualTo(OutboxRequeueOutcome.NOT_FOUND);
    }

    /**
     * 운영자가 응답을 못 보고 같은 요청을 다시 보내는 상황입니다. 두 번째는 이미 발행 대기가
     * 된 이벤트를 만나므로 거절되어야 합니다. 다시 되돌려 주면 relay 가 이미 가져간 이벤트의
     * 선점이 풀려 같은 이벤트가 두 번 나갑니다.
     */
    @Test
    @DisplayName("같은 요청을 다시 보내면 두 번째는 거절한다")
    void requeue_rejectsRepeatedRequest() {
        OutboxEvent saved = saveFailed(NOW.minus(1, ChronoUnit.HOURS));

        assertThat(outboxFailureService.requeue(saved.getEventId(), NOW))
            .isEqualTo(OutboxRequeueOutcome.REQUEUED);
        assertThat(outboxFailureService.requeue(saved.getEventId(), NOW))
            .isEqualTo(OutboxRequeueOutcome.NOT_FAILED);
    }

    /**
     * 운영자 둘이 같은 이벤트를 동시에 되돌리는 상황입니다.
     *
     * <p>대상을 잠그고 읽으므로 전이는 한 번만 일어나야 합니다. 잠금이 없으면 둘 다 최종 실패
     * 상태를 읽고 둘 다 성공으로 응답합니다. 기록이 실제와 어긋납니다.
     */
    @Test
    @DisplayName("두 요청이 동시에 들어와도 전이는 한 번만 일어난다")
    void requeue_concurrentRequests_transitionOnce() throws Exception {
        OutboxEvent saved = saveFailed(NOW.minus(1, ChronoUnit.HOURS));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<OutboxRequeueOutcome>> results = List.of(
                executor.submit(() -> {
                    start.await();
                    return outboxFailureService.requeue(saved.getEventId(), NOW);
                }),
                executor.submit(() -> {
                    start.await();
                    return outboxFailureService.requeue(saved.getEventId(), NOW);
                }));

            start.countDown();

            List<OutboxRequeueOutcome> outcomes = new ArrayList<>();
            for (Future<OutboxRequeueOutcome> result : results) {
                outcomes.add(result.get(10, TimeUnit.SECONDS));
            }

            assertThat(outcomes).containsExactlyInAnyOrder(
                OutboxRequeueOutcome.REQUEUED, OutboxRequeueOutcome.NOT_FAILED);
            assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(1);
            assertThat(outboxEventRepository.countByStatus(OutboxStatus.FAILED)).isZero();
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 건너뛰기는 발행에 성공했다는 뜻이 아닙니다. 보내지 않아도 된다는 업무 판단과 그 책임을
     * 남기는 종결이므로, 누가 언제 왜 라는 세 가지가 함께 있어야 단순히 지운 것과 구분됩니다.
     */
    @Test
    @DisplayName("최종 실패 이벤트를 건너뛰고 처리자·시각·사유를 남긴다")
    void skip_recordsAuditInformation() {
        OutboxEvent saved = saveFailed(NOW.minus(1, ChronoUnit.HOURS));

        assertThat(outboxFailureService.skip(saved.getEventId(), ACTOR_ID, "업무 영향 확인함", NOW))
            .isEqualTo(OutboxSkipOutcome.SKIPPED);

        OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
        assertThat(after.getSkippedBy()).isEqualTo(ACTOR_ID);
        assertThat(after.getSkippedAt()).isEqualTo(NOW);
        assertThat(after.getSkipReason()).isEqualTo("업무 영향 확인함");
        // 왜 실패했었는지와 왜 보내지 않기로 했는지는 다른 정보입니다. 둘 다 남깁니다.
        assertThat(after.getLastError()).isEqualTo("발행 실패");
    }

    @Test
    @DisplayName("최종 실패 상태가 아닌 이벤트는 건너뛸 수 없다")
    void skip_rejectsNonFailedEvent() {
        OutboxEvent pending = save(NOW);

        assertThat(outboxFailureService.skip(pending.getEventId(), ACTOR_ID, "사유", NOW))
            .isEqualTo(OutboxSkipOutcome.NOT_FAILED);
        assertThat(outboxEventRepository.findById(pending.getId()).orElseThrow().getStatus())
            .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("없는 eventId는 건너뛸 수 없다")
    void skip_rejectsUnknownEventId() {
        assertThat(outboxFailureService.skip(UUID.randomUUID(), ACTOR_ID, "사유", NOW))
            .isEqualTo(OutboxSkipOutcome.NOT_FOUND);
    }

    @Test
    @DisplayName("사유가 비어 있으면 건너뛰기를 거부한다")
    void skip_rejectsBlankReason() {
        OutboxEvent saved = saveFailed(NOW);

        assertThatThrownBy(() ->
            outboxFailureService.skip(saved.getEventId(), ACTOR_ID, "   ", NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 같은 요청이 두 번 들어와도 결과는 "그 이벤트는 건너뛴 상태다"로 같아야 합니다. 다만
     * 실제로 판단한 사람과 시각은 처음 전환한 쪽이므로 감사 정보를 덮어쓰지 않습니다.
     */
    @Test
    @DisplayName("이미 건너뛴 이벤트를 다시 건너뛰어도 감사 정보를 덮어쓰지 않는다")
    void skip_isIdempotentAndKeepsFirstAudit() {
        OutboxEvent saved = saveFailed(NOW.minus(1, ChronoUnit.HOURS));
        outboxFailureService.skip(saved.getEventId(), ACTOR_ID, "처음 판단", NOW);

        assertThat(outboxFailureService.skip(
            saved.getEventId(), OTHER_ACTOR_ID, "나중 판단", NOW.plusSeconds(60)))
            .isEqualTo(OutboxSkipOutcome.ALREADY_SKIPPED);

        OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getSkippedBy()).isEqualTo(ACTOR_ID);
        assertThat(after.getSkippedAt()).isEqualTo(NOW);
        assertThat(after.getSkipReason()).isEqualTo("처음 판단");
    }

    /**
     * 운영자 둘이 같은 이벤트를 동시에 건너뛰는 상황입니다. 전이는 한 번만 일어나고 감사
     * 정보도 한 벌만 남아야 합니다.
     */
    @Test
    @DisplayName("두 요청이 동시에 들어와도 감사 정보는 한 벌만 남는다")
    void skip_concurrentRequests_keepOneAudit() throws Exception {
        OutboxEvent saved = saveFailed(NOW.minus(1, ChronoUnit.HOURS));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<OutboxSkipOutcome>> results = List.of(
                executor.submit(() -> {
                    start.await();
                    return outboxFailureService.skip(
                        saved.getEventId(), ACTOR_ID, "처음 판단", NOW);
                }),
                executor.submit(() -> {
                    start.await();
                    return outboxFailureService.skip(
                        saved.getEventId(), OTHER_ACTOR_ID, "나중 판단", NOW);
                }));

            start.countDown();

            List<OutboxSkipOutcome> outcomes = new ArrayList<>();
            for (Future<OutboxSkipOutcome> result : results) {
                outcomes.add(result.get(10, TimeUnit.SECONDS));
            }

            assertThat(outcomes).containsExactlyInAnyOrder(
                OutboxSkipOutcome.SKIPPED, OutboxSkipOutcome.ALREADY_SKIPPED);

            OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(OutboxStatus.SKIPPED);
            assertThat(after.getSkippedBy()).isIn(ACTOR_ID, OTHER_ACTOR_ID);
            assertThat(after.getSkipReason()).isIn("처음 판단", "나중 판단");
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 애플리케이션을 거치지 않고 들어오는 변경도 막아야 합니다. 감사 정보 없는 SKIPPED 행은
     * 나중에 보고 무슨 일이 있었는지 알 수 없어 단순히 지운 것과 다르지 않습니다.
     */
    @Test
    @DisplayName("감사 정보 없는 SKIPPED 행은 스키마가 거부한다")
    void schema_rejectsSkippedWithoutAudit() {
        OutboxEvent saved = saveFailed(NOW);

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'SKIPPED' WHERE id = ?", saved.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("사유가 공백뿐인 SKIPPED 행은 스키마가 거부한다")
    void schema_rejectsSkippedWithBlankReason() {
        OutboxEvent saved = saveFailed(NOW);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            UPDATE outbox_events
            SET status = 'SKIPPED', skipped_by = ?, skipped_at = ?, skip_reason = '   '
            WHERE id = ?
            """, ACTOR_ID, Timestamp.from(NOW), saved.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
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
