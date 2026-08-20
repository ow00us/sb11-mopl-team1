package com.mopl.follow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.follow.repository.FollowRepository;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.outbox.OutboxEvent;
import com.mopl.global.outbox.OutboxEventRepository;
import com.mopl.global.outbox.OutboxRecorder;
import com.mopl.global.outbox.OutboxStatus;
import com.mopl.user.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FollowService.follow() 가 실제 PostgreSQL 에서 outbox_events 를 계약대로 기록하는지 검증한다.
 *
 * <p>계약 참조: docs/07-kafka-outbox-contract.md §8.1 (follow.created 카탈로그) · §9 (deduplication_key).
 *
 * <p>테스트 클래스에 {@code @Transactional} 을 붙이지 않는다. 붙이면 FollowService 의 REQUIRED
 * 트랜잭션이 테스트 트랜잭션에 참여해 커밋되지 않아 outbox 행이 관찰되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(FollowServiceOutboxIntegrationTest.DomainCaller.class)
class FollowServiceOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired FollowService followService;
    @Autowired FollowRepository followRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxRecorder outboxRecorder;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DomainCaller domainCaller;

    @SuppressWarnings("unused") @Autowired UserRepository userRepository;

    private static final UUID FOLLOWER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FOLLOWEE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID FOLLOWEE_ID_2 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM follows");
        jdbcTemplate.update("DELETE FROM users");

        Instant now = Instant.now();
        for (UUID uid : new UUID[]{FOLLOWER_ID, FOLLOWEE_ID, FOLLOWEE_ID_2}) {
            jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at, email, password_hash, name, role) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                uid, Timestamp.from(now), Timestamp.from(now),
                uid + "@test.com", "hash", "user-" + uid, "USER");
        }
    }

    @Test
    @DisplayName("follow() 성공 시 outbox_events 에 계약 §8.1 필드를 채운 1행이 저장된다")
    void follow_success_recordsOutboxRowWithContractCompliantFields() throws Exception {
        FollowResult result = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);
        UUID followId = result.dto().id();

        assertThat(outboxEventRepository.count()).isEqualTo(1);

        OutboxEvent recorded = outboxEventRepository.findAll().get(0);
        assertThat(recorded.getType()).isEqualTo("follow.created");
        assertThat(recorded.getVersion()).isEqualTo(1);
        assertThat(recorded.getAggregateId()).isEqualTo(followId);
        assertThat(recorded.getOccurredAt()).isEqualTo(
            followRepository.findById(followId).orElseThrow().getCreatedAt());
        assertThat(recorded.getPartitionKey()).isEqualTo(followId.toString());
        assertThat(recorded.getOrderingScope()).isEqualTo("NONE");
        assertThat(recorded.getDeduplicationKey()).isEqualTo("follow.created:" + followId);
        assertThat(recorded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recorded.getAttempts()).isZero();
        assertThat(recorded.getNextAttemptAt()).isNotNull();

        var payload = objectMapper.readTree(recorded.getPayload());
        assertThat(payload.get("followerId").asText()).isEqualTo(FOLLOWER_ID.toString());
        assertThat(payload.get("followeeId").asText()).isEqualTo(FOLLOWEE_ID.toString());
    }

    @Test
    @DisplayName("같은 관계로 두 번 follow() 해도 outbox_events 는 1행만 남는다 (서비스가 upsert=0 시 record 스킵)")
    void follow_duplicate_doesNotRecordAdditionalOutboxRow() {
        FollowResult first = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);
        FollowResult second = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 관계 두 개를 follow() 하면 각각 별도 deduplication_key 로 outbox_events 2행이 저장된다")
    void follow_multipleRelations_recordsSeparateOutboxRowsWithDistinctDeduplicationKeys() {
        FollowResult r1 = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);
        FollowResult r2 = followService.follow(FOLLOWER_ID, FOLLOWEE_ID_2);

        assertThat(outboxEventRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.findAll())
            .extracting(OutboxEvent::getDeduplicationKey)
            .containsExactlyInAnyOrder(
                "follow.created:" + r1.dto().id(),
                "follow.created:" + r2.dto().id());
    }

    @Test
    @DisplayName("같은 deduplication_key 로 두 번째 record 하면 UNIQUE 제약이 거부하고 outbox_events 는 1행만 유지된다")
    void duplicateDeduplicationKey_isRejectedByUniqueConstraint() {
        FollowResult first = followService.follow(FOLLOWER_ID, FOLLOWEE_ID);
        UUID followId = first.dto().id();
        String dedupKey = "follow.created:" + followId;

        // 같은 dedup key 로 두 번째 기록을 시도한다. eventId 는 새로 만들어 eventId UNIQUE 충돌을 배제하고,
        // deduplication_key UNIQUE 제약이 순수하게 두 번째 기록을 막는지 확인한다.
        EventEnvelope duplicate = new EventEnvelope(
            UUID.randomUUID(),
            "follow.created",
            1,
            Instant.now(),
            followId,
            objectMapper.valueToTree(Map.of(
                "followerId", FOLLOWER_ID.toString(),
                "followeeId", FOLLOWEE_ID.toString())));

        assertThatThrownBy(() -> domainCaller.record(duplicate, followId.toString(), "NONE", dedupKey))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    /**
     * OutboxRecorder 는 {@code MANDATORY} 라 트랜잭션 밖에서 호출하면 거부된다. 테스트에서 직접 호출할
     * 때 트랜잭션 경계를 열어 주는 헬퍼다.
     */
    static class DomainCaller {

        private final OutboxRecorder outboxRecorder;

        DomainCaller(OutboxRecorder outboxRecorder) {
            this.outboxRecorder = outboxRecorder;
        }

        @Transactional
        public void record(EventEnvelope envelope, String partitionKey, String orderingScope,
            String deduplicationKey) {
            outboxRecorder.record(envelope, partitionKey, orderingScope, deduplicationKey);
        }
    }
}