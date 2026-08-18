package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.MoplTopics;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Outbox 레코드가 Kafka 로 나가고 그 결과가 상태에 반영되는지 검증합니다.
 *
 * <p>실제 broker 가 필요합니다. 발행 확인을 받은 뒤에만 완료로 바뀌는지가 핵심인데, 템플릿을
 * 모킹하면 그 순서가 검증되지 않습니다.
 *
 * <p>스케줄 실행은 끕니다. 주기 실행이 준비 중인 데이터를 먼저 가져가면 결과가 흔들립니다.
 * 대신 relay 를 직접 만들어 호출 시점을 테스트가 정합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(OutboxRelayIntegrationTest.RelayConfig.class)
@TestPropertySource(properties = "mopl.kafka.topic.auto-create=true")
class OutboxRelayIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final Instant NOW = Instant.parse("2026-08-15T03:00:00Z");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    /** 발행 결과를 읽는 프로브입니다. 컨테이너를 한 번만 띄우려고 클래스 전체에서 공유합니다. */
    static KafkaConsumer<String, String> probe;

    @Autowired
    OutboxRelay outboxRelay;

    @Autowired
    MutableClock relayClock;

    @Autowired
    OutboxClaimer outboxClaimer;

    @Autowired
    KafkaTemplate<String, EventEnvelope> eventKafkaTemplate;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    /**
     * relay 를 직접 만들어 등록합니다.
     *
     * <p>{@code mopl.outbox.relay.enabled} 가 test 프로파일에서 꺼져 있어 운영 빈은 만들어지지
     * 않습니다. 스케줄 없이 호출 시점을 테스트가 정하기 위한 구성입니다.
     */
    @TestConfiguration
    static class RelayConfig {

        @Bean
        MutableClock relayClock() {
            return new MutableClock(NOW);
        }

        @Bean
        OutboxRelay outboxRelay(
            OutboxClaimer outboxClaimer,
            OutboxStatusWriter outboxStatusWriter,
            KafkaTemplate<String, EventEnvelope> eventKafkaTemplate,
            ObjectMapper objectMapper,
            MutableClock relayClock
        ) {
            return new OutboxRelay(
                outboxClaimer, outboxStatusWriter, eventKafkaTemplate, objectMapper,
                100, Duration.ofSeconds(10), relayClock);
        }
    }

    @BeforeAll
    static void openProbe() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-probe");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        probe = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        probe.subscribe(List.of(MoplTopics.FOLLOW_EVENTS));
    }

    @AfterAll
    static void closeProbe() {
        if (probe != null) {
            probe.close();
        }
    }

    @BeforeEach
    void clear() {
        outboxEventRepository.deleteAll();
        relayClock.set(NOW);
    }

    private OutboxEvent pending(String type, UUID aggregateId, String payload) {
        return new OutboxEvent(
            UUID.randomUUID(), type, 1, aggregateId, NOW.minusSeconds(1),
            payload, aggregateId.toString(), "AGGREGATE", NOW.minusSeconds(1));
    }

    /**
     * 지정한 키의 레코드를 찾을 때까지 읽습니다.
     *
     * <p>프로브가 클래스 전체에서 하나이므로 앞선 케이스의 레코드가 남아 있습니다. 키로 걸러야
     * 다른 케이스의 결과를 자기 것으로 착각하지 않습니다.
     */
    private ConsumerRecord<String, String> awaitRecordWithKey(String key) {
        Instant deadline = Instant.now().plus(PROBE_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = probe.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("키가 " + key + " 인 레코드를 받지 못했습니다.");
    }

    @Test
    @DisplayName("발행 대기 레코드를 Kafka로 보내고 발행 완료로 표시한다")
    void publishClaimed_sendsToKafkaAndMarksPublished() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent saved = outboxEventRepository.saveAndFlush(
            pending("follow.created", aggregateId, "{\"followerId\":\"a\",\"followeeId\":\"b\"}"));

        int published = outboxRelay.publishClaimed();

        assertThat(published).isEqualTo(1);

        ConsumerRecord<String, String> record = awaitRecordWithKey(aggregateId.toString());
        EventEnvelope got = objectMapper.readValue(record.value(), EventEnvelope.class);

        // 저장한 값이 그대로 나가야 합니다. eventId 가 바뀌면 소비자 멱등 판정이 깨집니다.
        assertThat(got.eventId()).isEqualTo(saved.getEventId());
        assertThat(got.type()).isEqualTo("follow.created");
        assertThat(got.version()).isEqualTo(1);
        assertThat(got.aggregateId()).isEqualTo(aggregateId);
        assertThat(got.occurredAt()).isEqualTo(saved.getOccurredAt());
        assertThat(got.payload().get("followerId").asText()).isEqualTo("a");

        // 메시지 키가 곧 파티션 배정 기준입니다. 여기가 어긋나면 orderingScope 가 무엇이든
        // 같은 aggregate 의 이벤트가 다른 파티션으로 흩어집니다.
        assertThat(record.key()).isEqualTo(saved.getPartitionKey());

        OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(after.getPublishedAt()).isEqualTo(NOW);
        // 선점을 남겨두면 만료 lease 회수 조회가 이미 끝난 레코드를 계속 훑습니다.
        assertThat(after.getClaimOwner()).isNull();
        assertThat(after.getClaimExpiresAt()).isNull();
        assertThat(after.getLastError()).isNull();
        assertThat(after.getPartitionKey()).isEqualTo(saved.getPartitionKey());
        assertThat(after.getOrderingScope()).isEqualTo("AGGREGATE");
    }

    /**
     * 발행에 성공한 뒤 상태를 남기지 못한 경우입니다.
     *
     * <p>이때 실패로 기록하면 이미 나간 이벤트가 시도 실패로 집계되고, 반복되면 발행에 성공한
     * 이벤트가 최종 실패로 남습니다. 선점을 그대로 두어 lease 만료로 회수되게 해야 합니다.
     */
    @Test
    @DisplayName("발행 후 상태를 남기지 못하면 실패로 집계하지 않고 선점을 유지한다")
    void publishClaimed_keepsClaimWhenStatusWriteFails() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent saved = outboxEventRepository.saveAndFlush(
            pending("follow.created", aggregateId, "{\"followerId\":\"a\"}"));

        OutboxStatusWriter failingWriter = new OutboxStatusWriter(outboxEventRepository) {
            @Override
            public void markPublished(UUID id, Instant publishedAt) {
                throw new IllegalStateException("데이터베이스 연결 실패");
            }
        };
        OutboxRelay relay = new OutboxRelay(
            outboxClaimer, failingWriter, eventKafkaTemplate, objectMapper,
            100, Duration.ofSeconds(10), relayClock);

        assertThat(relay.publishClaimed()).isZero();

        // 발행 자체는 끝났어야 합니다.
        awaitRecordWithKey(aggregateId.toString());

        OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getLastError()).isNull();
        // 선점이 남아 있어야 lease 만료 전까지 다른 인스턴스가 다시 가져가지 않습니다.
        assertThat(after.getClaimOwner()).isNotNull();
        assertThat(after.getClaimExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("발행 완료 레코드는 다시 발행하지 않는다")
    void publishClaimed_skipsAlreadyPublished() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent saved = outboxEventRepository.saveAndFlush(
            pending("follow.created", aggregateId, "{\"followerId\":\"a\"}"));
        jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'PUBLISHED' WHERE id = ?", saved.getId());

        assertThat(outboxRelay.publishClaimed()).isZero();
    }

    @Test
    @DisplayName("발행할 수 없는 이벤트는 완료로 바꾸지 않고 실패를 남긴다")
    void publishClaimed_keepsRecordPendingWhenPublishFails() {
        // 토픽이 정해지지 않은 타입입니다. broker 를 건드리지 않고 발행 실패를 만들 수 있습니다.
        OutboxEvent saved = outboxEventRepository.saveAndFlush(
            pending("unknown.created", UUID.randomUUID(), "{\"a\":1}"));

        int published = outboxRelay.publishClaimed();

        assertThat(published).isZero();

        OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getPublishedAt()).isNull();
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(after.getLastError()).contains("unknown.created");
        // 선점을 풀어야 다음 주기가 바로 다시 시도할 수 있습니다.
        assertThat(after.getClaimOwner()).isNull();
        assertThat(after.getClaimExpiresAt()).isNull();
    }

    @Test
    @DisplayName("실패한 레코드는 다음 주기에 다시 발행 대상이 된다")
    void publishClaimed_retriesFailedRecordOnNextRun() {
        OutboxEvent saved = outboxEventRepository.saveAndFlush(
            pending("unknown.created", UUID.randomUUID(), "{\"a\":1}"));

        outboxRelay.publishClaimed();
        relayClock.set(NOW.plusSeconds(1));
        outboxRelay.publishClaimed();

        OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getAttempts()).isEqualTo(2);
    }

    /**
     * relay 가 선점만 하고 종료된 상황입니다.
     *
     * <p>lease 가 만료되면 다른 인스턴스가 회수해 발행을 마쳐야 합니다. 회수하지 못하면 이미
     * 커밋된 도메인 변경에 대한 이벤트가 발행되지 않은 채로 남습니다.
     */
    @Test
    @DisplayName("lease가 만료된 레코드를 회수해 발행한다")
    void publishClaimed_reclaimsExpiredLease() {
        UUID aggregateId = UUID.randomUUID();
        OutboxEvent saved = outboxEventRepository.saveAndFlush(
            pending("follow.created", aggregateId, "{\"followerId\":\"a\"}"));

        jdbcTemplate.update(
            "UPDATE outbox_events SET claim_owner = 'dead-relay', claim_expires_at = ? WHERE id = ?",
            Timestamp.from(NOW.minusSeconds(1)), saved.getId());

        assertThat(outboxRelay.publishClaimed()).isEqualTo(1);

        awaitRecordWithKey(aggregateId.toString());

        OutboxEvent after = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(after.getClaimOwner()).isNull();
    }

    @Test
    @DisplayName("발행 대기 레코드가 없으면 아무 것도 보내지 않는다")
    void publishClaimed_returnsZeroWhenNothingPending() {
        assertThat(outboxRelay.publishClaimed()).isZero();
    }

    /**
     * 한 batch 안에서 실패가 섞여도 성공한 건은 완료로 남아야 합니다.
     *
     * <p>상태 반영을 하나의 트랜잭션으로 묶으면 실패 한 건 때문에 앞서 발행에 성공한 건들이
     * 발행 대기로 되돌아가고, 이미 broker 로 나간 이벤트가 다음 주기에 그대로 다시 나갑니다.
     */
    @Test
    @DisplayName("batch 안의 한 건이 실패해도 나머지는 발행 완료로 남는다")
    void publishClaimed_failureDoesNotRollBackOtherRecords() {
        List<OutboxEvent> saved = new ArrayList<>();
        saved.add(outboxEventRepository.saveAndFlush(
            pending("follow.created", UUID.randomUUID(), "{\"followerId\":\"a\"}")));
        saved.add(outboxEventRepository.saveAndFlush(
            pending("unknown.created", UUID.randomUUID(), "{\"a\":1}")));
        saved.add(outboxEventRepository.saveAndFlush(
            pending("follow.created", UUID.randomUUID(), "{\"followerId\":\"c\"}")));

        assertThat(outboxRelay.publishClaimed()).isEqualTo(2);

        assertThat(outboxEventRepository.findById(saved.get(0).getId()).orElseThrow().getStatus())
            .isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outboxEventRepository.findById(saved.get(1).getId()).orElseThrow().getStatus())
            .isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxEventRepository.findById(saved.get(2).getId()).orElseThrow().getStatus())
            .isEqualTo(OutboxStatus.PUBLISHED);
    }
}
