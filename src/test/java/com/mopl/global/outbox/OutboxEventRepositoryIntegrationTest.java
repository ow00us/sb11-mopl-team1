package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Outbox 저장 모델을 실제 데이터베이스로 검증합니다.
 *
 * <p>스키마 제약과 jsonb 왕복은 실제 PostgreSQL 이 있어야 확인됩니다. 인메모리 대체나
 * 모킹으로는 유니크 제약, 체크 제약, jsonb 타입 매핑이 모두 검증되지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OutboxEventRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        outboxEventRepository.deleteAll();
    }

    private OutboxEvent pendingEvent(String partitionKey, String payload, Instant occurredAt) {
        return new OutboxEvent(
            UUID.randomUUID(),
            "follow.created",
            1,
            UUID.randomUUID(),
            occurredAt,
            payload,
            partitionKey,
            "NONE",
            occurredAt);
    }

    private OutboxEvent pendingEvent(String partitionKey) {
        return pendingEvent(partitionKey, "{\"followerId\":\"a\",\"followeeId\":\"b\"}",
            Instant.parse("2026-08-14T03:00:00Z"));
    }

    @Test
    @DisplayName("envelope 원본과 relay 상태 필드가 모두 보존된다")
    void save_preservesAllFields() {
        Instant occurredAt = Instant.parse("2026-08-14T03:00:00Z").plusNanos(123_456_000L);
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();

        OutboxEvent saved = outboxEventRepository.saveAndFlush(new OutboxEvent(
            eventId, "premiere.upcoming", 2, aggregateId, occurredAt,
            "{\"contentId\":\"c1\",\"startsAt\":\"2026-08-14T04:00:00Z\"}",
            "content-1", "contentId", occurredAt));

        OutboxEvent found = outboxEventRepository.findByEventId(eventId).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getType()).isEqualTo("premiere.upcoming");
        assertThat(found.getVersion()).isEqualTo(2);
        assertThat(found.getAggregateId()).isEqualTo(aggregateId);
        assertThat(found.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(found.getPartitionKey()).isEqualTo("content-1");
        assertThat(found.getOrderingScope()).isEqualTo("contentId");

        assertThat(found.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(found.getAttempts()).isZero();
        assertThat(found.getNextAttemptAt()).isEqualTo(occurredAt);
        assertThat(found.getClaimOwner()).isNull();
        assertThat(found.getClaimExpiresAt()).isNull();
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getLastError()).isNull();

        // BaseEntity 감사 필드도 채워집니다.
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("중첩 구조와 한글을 포함한 payload가 내용 손실 없이 왕복한다")
    void payload_roundTripsWithoutLoss() throws Exception {
        String payload = """
            {"contentId":"c1","title":"한글 제목","tags":["드라마","코미디"],
             "nested":{"a":1,"b":[true,null,2.5]},"count":9007199254740993}
            """;

        UUID eventId = UUID.randomUUID();
        outboxEventRepository.saveAndFlush(new OutboxEvent(
            eventId, "premiere.upcoming", 1, UUID.randomUUID(),
            Instant.parse("2026-08-14T03:00:00Z"), payload,
            "content-1", "contentId",
            Instant.parse("2026-08-14T03:00:00Z")));

        String stored = outboxEventRepository.findByEventId(eventId).orElseThrow().getPayload();

        // jsonb 는 키 순서와 공백을 정규화하므로 문자열 비교가 아니라 내용으로 비교합니다.
        JsonNode expected = objectMapper.readTree(payload);
        JsonNode actual = objectMapper.readTree(stored);
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.get("title").asText()).isEqualTo("한글 제목");
        assertThat(actual.get("nested").get("b").get(2).decimalValue().toPlainString())
            .isEqualTo("2.5");
        assertThat(actual.get("count").asText()).isEqualTo("9007199254740993");
    }

    @Test
    @DisplayName("payload가 문자열이 아니라 실제 jsonb로 저장되어 DB에서 파고들 수 있다")
    void payload_isStoredAsQueryableJsonb() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-14T03:00:00Z");
        outboxEventRepository.saveAndFlush(new OutboxEvent(
            eventId, "premiere.upcoming", 1, UUID.randomUUID(), occurredAt,
            "{\"contentId\":\"c1\",\"nested\":{\"depth\":2}}",
            "content-1", "contentId", occurredAt));

        // jsonb 를 택한 이유가 운영 조회와 replay 도구의 내용 확인입니다. 값이 JSON 문자열로
        // 이중 인코딩되면 이 연산자가 동작하지 않으므로, 타입 매핑을 여기서 고정합니다.
        String contentId = jdbcTemplate.queryForObject(
            "SELECT payload->>'contentId' FROM outbox_events WHERE event_id = ?",
            String.class, eventId);
        Integer depth = jdbcTemplate.queryForObject(
            "SELECT (payload->'nested'->>'depth')::int FROM outbox_events WHERE event_id = ?",
            Integer.class, eventId);

        assertThat(contentId).isEqualTo("c1");
        assertThat(depth).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 eventId는 두 번 저장되지 않는다")
    void duplicateEventId_isRejected() {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-08-14T03:00:00Z");

        outboxEventRepository.saveAndFlush(new OutboxEvent(
            eventId, "follow.created", 1, UUID.randomUUID(), occurredAt,
            "{}", "k1", "NONE", occurredAt));

        assertThatThrownBy(() -> outboxEventRepository.saveAndFlush(new OutboxEvent(
            eventId, "follow.created", 1, UUID.randomUUID(), occurredAt,
            "{}", "k2", "NONE", occurredAt)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("상태별로 조회하고 셀 수 있다")
    void findAndCountByStatus() {
        outboxEventRepository.saveAndFlush(pendingEvent("key-1"));
        outboxEventRepository.saveAndFlush(pendingEvent("key-2"));

        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).isEqualTo(2);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED)).isZero();
        assertThat(outboxEventRepository
            .findByStatusOrderByOccurredAtAsc(OutboxStatus.PENDING, Limit.of(10)))
            .hasSize(2);
        assertThat(outboxEventRepository
            .findByStatusOrderByOccurredAtAsc(OutboxStatus.FAILED, Limit.of(10)))
            .isEmpty();
    }

    @Test
    @DisplayName("발행 대기 조회는 시도 시각이 지난 건만 오래된 순으로 돌려준다")
    void findPending_respectsNextAttemptAtAndOrder() {
        Instant base = Instant.parse("2026-08-14T03:00:00Z");
        Instant now = base.plus(10, ChronoUnit.MINUTES);

        OutboxEvent older = pendingEvent("key-older", "{}", base);
        OutboxEvent newer = pendingEvent("key-newer", "{}", base.plus(1, ChronoUnit.MINUTES));
        // 아직 시도 시각이 오지 않은 건입니다.
        OutboxEvent notYet = pendingEvent("key-not-yet", "{}", base.plus(1, ChronoUnit.HOURS));

        outboxEventRepository.saveAllAndFlush(List.of(newer, older, notYet));

        List<OutboxEvent> pending = outboxEventRepository
            .findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                OutboxStatus.PENDING, now, Limit.of(10));

        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getPartitionKey()).isEqualTo("key-older");
        assertThat(pending.get(1).getPartitionKey()).isEqualTo("key-newer");
    }

    @Test
    @DisplayName("대기 조회가 인덱스 정렬을 그대로 쓰고 별도 정렬을 붙이지 않는다")
    void findPending_usesIndexOrderWithoutSort() {
        outboxEventRepository.saveAndFlush(pendingEvent("key-plan"));

        // 이 조회는 relay 가 주기마다 반복합니다. 정렬 기준이 인덱스와 어긋나면 매번 정렬이
        // 붙으므로 실행 계획으로 고정합니다.
        //
        // seqscan 을 끄는 이유는 행이 적을 때 플래너가 전체 스캔을 택해 계획이 흔들리기
        // 때문입니다. 인덱스를 쓰도록 강제한 상태에서도 Sort 가 붙는다면 정렬 기준이 인덱스와
        // 맞지 않는다는 뜻이고, 그것이 이 테스트가 잡으려는 회귀입니다.
        // 세션 설정이므로 같은 커넥션에서 실행해야 합니다.
        String plan = jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                StringBuilder lines = new StringBuilder();
                try (var rows = statement.executeQuery("""
                    EXPLAIN SELECT * FROM outbox_events
                    WHERE status = 'PENDING'
                      AND next_attempt_at <= '2026-08-14T04:00:00Z'::timestamptz
                    ORDER BY next_attempt_at ASC, id ASC
                    LIMIT 10
                    """)) {
                    while (rows.next()) {
                        lines.append(rows.getString(1)).append('\n');
                    }
                }
                statement.execute("SET enable_seqscan = on");
                return lines.toString();
            }
        });

        assertThat(plan).contains("idx_outbox_events_pending");
        assertThat(plan).doesNotContain("Sort");
    }

    @Test
    @DisplayName("정의되지 않은 상태 값은 체크 제약이 거부한다")
    void unknownStatus_isRejectedByCheckConstraint() {
        OutboxEvent saved = outboxEventRepository.saveAndFlush(pendingEvent("key-check"));

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'UNKNOWN' WHERE id = ?", saved.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("마이그레이션이 제약과 인덱스를 실제로 만든다")
    void migration_createsConstraintsAndIndexes() {
        List<String> indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'outbox_events'", String.class);

        assertThat(indexes).contains(
            "idx_outbox_events_pending",
            "idx_outbox_events_claim_expires_at",
            "idx_outbox_events_partition_order",
            "uk_outbox_events_event_id");
    }
}
