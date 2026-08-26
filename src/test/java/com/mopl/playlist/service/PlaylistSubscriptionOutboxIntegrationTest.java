package com.mopl.playlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.outbox.OutboxEvent;
import com.mopl.global.outbox.OutboxEventRepository;
import com.mopl.global.outbox.OutboxRecorder;
import com.mopl.global.outbox.OutboxStatus;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
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
 * PlaylistServiceImpl.subscribe() 가 실제 PostgreSQL 에서 outbox_events 를 계약대로 기록하는지
 * 검증한다.
 *
 * <p>계약 참조: docs/07-kafka-outbox-contract.md §8.2 (playlist.subscription.created 카탈로그) ·
 * §9 (deduplication_key).
 *
 * <p>테스트 클래스에 {@code @Transactional} 을 붙이지 않는다. 붙이면 도메인 트랜잭션이 테스트
 * 트랜잭션에 참여해 커밋되지 않아 outbox 행이 관찰되지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(PlaylistSubscriptionOutboxIntegrationTest.DomainCaller.class)
class PlaylistSubscriptionOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired PlaylistServiceImpl playlistService;
    @Autowired PlaylistSubscriptionRepository subscriptionRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxRecorder outboxRecorder;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DomainCaller domainCaller;

    private static final UUID OWNER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_ID_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SUBSCRIBER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID PLAYLIST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYLIST_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_events");
        jdbcTemplate.update("DELETE FROM playlist_subscriptions");
        jdbcTemplate.update("DELETE FROM playlists");
        jdbcTemplate.update("DELETE FROM users");

        Instant now = Instant.now();
        for (UUID uid : new UUID[]{OWNER_ID, OWNER_ID_2, SUBSCRIBER_ID}) {
            jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at, email, password_hash, name, role) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                uid, Timestamp.from(now), Timestamp.from(now),
                uid + "@test.com", "hash", "user-" + uid, "USER");
        }
        insertPlaylist(PLAYLIST_ID, OWNER_ID, now);
        insertPlaylist(PLAYLIST_ID_2, OWNER_ID_2, now);
    }

    private void insertPlaylist(UUID playlistId, UUID ownerId, Instant now) {
        jdbcTemplate.update(
            "INSERT INTO playlists (id, created_at, updated_at, owner_id, title, description, subscriber_count) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0)",
            playlistId, Timestamp.from(now), Timestamp.from(now),
            ownerId, "제목-" + playlistId, "설명");
    }

    @Test
    @DisplayName("subscribe() 성공 시 outbox_events 에 계약 §8.2 필드를 채운 1행이 저장된다")
    void subscribe_success_recordsOutboxRowWithContractCompliantFields() throws Exception {
        playlistService.subscribe(PLAYLIST_ID, SUBSCRIBER_ID);

        assertThat(outboxEventRepository.count()).isEqualTo(1);

        UUID subscriptionId = subscriptionRepository
                .findByPlaylistIdAndSubscriberId(PLAYLIST_ID, SUBSCRIBER_ID)
                .orElseThrow()
                .getId();

        OutboxEvent recorded = outboxEventRepository.findAll().get(0);
        assertThat(recorded.getType()).isEqualTo("playlist.subscription.created");
        assertThat(recorded.getVersion()).isEqualTo(1);
        assertThat(recorded.getAggregateId()).isEqualTo(subscriptionId);
        assertThat(recorded.getPartitionKey()).isEqualTo(subscriptionId.toString());
        assertThat(recorded.getOrderingScope()).isEqualTo("NONE");
        assertThat(recorded.getDeduplicationKey())
                .isEqualTo("playlist.subscription.created:" + subscriptionId);
        assertThat(recorded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recorded.getAttempts()).isZero();
        assertThat(recorded.getNextAttemptAt()).isNotNull();

        var payload = objectMapper.readTree(recorded.getPayload());
        assertThat(payload.get("playlistId").asText()).isEqualTo(PLAYLIST_ID.toString());
        assertThat(payload.get("playlistOwnerId").asText()).isEqualTo(OWNER_ID.toString());
        assertThat(payload.get("subscriberId").asText()).isEqualTo(SUBSCRIBER_ID.toString());
    }

    @Test
    @DisplayName("같은 플레이리스트를 두 번 subscribe() 해도 outbox_events 는 1행만 남는다 (서비스가 rows=0 시 record 스킵)")
    void subscribe_duplicate_doesNotRecordAdditionalOutboxRow() {
        playlistService.subscribe(PLAYLIST_ID, SUBSCRIBER_ID);
        playlistService.subscribe(PLAYLIST_ID, SUBSCRIBER_ID);

        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 플레이리스트 두 개를 subscribe() 하면 각각 별도 deduplication_key 로 outbox_events 2행이 저장된다")
    void subscribe_multiplePlaylists_recordsSeparateOutboxRowsWithDistinctDeduplicationKeys() {
        playlistService.subscribe(PLAYLIST_ID, SUBSCRIBER_ID);
        playlistService.subscribe(PLAYLIST_ID_2, SUBSCRIBER_ID);

        UUID subscriptionId1 = subscriptionRepository
                .findByPlaylistIdAndSubscriberId(PLAYLIST_ID, SUBSCRIBER_ID).orElseThrow().getId();
        UUID subscriptionId2 = subscriptionRepository
                .findByPlaylistIdAndSubscriberId(PLAYLIST_ID_2, SUBSCRIBER_ID).orElseThrow().getId();

        assertThat(outboxEventRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.findAll())
                .extracting(OutboxEvent::getDeduplicationKey)
                .containsExactlyInAnyOrder(
                        "playlist.subscription.created:" + subscriptionId1,
                        "playlist.subscription.created:" + subscriptionId2);
    }

    @Test
    @DisplayName("자기 자신 플레이리스트 구독 차단 시 outbox_events 는 저장되지 않는다")
    void subscribe_selfSubscribe_doesNotRecordOutboxRow() {
        assertThatThrownBy(() -> playlistService.subscribe(PLAYLIST_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("같은 deduplication_key 로 두 번째 record 하면 UNIQUE 제약이 거부하고 outbox_events 는 1행만 유지된다")
    void duplicateDeduplicationKey_isRejectedByUniqueConstraint() {
        playlistService.subscribe(PLAYLIST_ID, SUBSCRIBER_ID);
        UUID subscriptionId = subscriptionRepository
                .findByPlaylistIdAndSubscriberId(PLAYLIST_ID, SUBSCRIBER_ID).orElseThrow().getId();
        String dedupKey = "playlist.subscription.created:" + subscriptionId;

        // 같은 dedup key 로 두 번째 기록을 시도한다. eventId 는 새로 만들어 eventId UNIQUE 충돌을 배제하고,
        // deduplication_key UNIQUE 제약이 순수하게 두 번째 기록을 막는지 확인한다.
        EventEnvelope duplicate = new EventEnvelope(
                UUID.randomUUID(),
                "playlist.subscription.created",
                1,
                Instant.now(),
                subscriptionId,
                objectMapper.valueToTree(Map.of(
                        "playlistId", PLAYLIST_ID.toString(),
                        "playlistOwnerId", OWNER_ID.toString(),
                        "subscriberId", SUBSCRIBER_ID.toString())));

        assertThatThrownBy(() -> domainCaller.record(duplicate, subscriptionId.toString(), "NONE", dedupKey))
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