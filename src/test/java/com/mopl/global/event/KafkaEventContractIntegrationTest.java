package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 공통 Kafka 기반이 계약대로 동작하는지 검증합니다.
 *
 * <p>도메인 리스너는 이 이슈 범위가 아니므로 테스트 전용 리스너를 씁니다. 검증 대상은
 * 공통 직렬화, 파티션 키 전달, 재시도 구분, DLT 라우팅, 컨테이너 생존입니다.
 *
 * <p>Kafka 컨테이너를 한 번만 띄우기 위해 모든 케이스를 한 클래스에 모았습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(KafkaEventContractIntegrationTest.TestListenerConfig.class)
@TestPropertySource(properties = {
    "mopl.kafka.topic.auto-create=true",
    "mopl.kafka.listener.auto-startup=true"
})
class KafkaEventContractIntegrationTest {

    private static final String TOPIC = MoplTopics.FOLLOW_EVENTS;
    private static final String DLT = MoplTopics.FOLLOW_EVENTS + MoplTopics.DLT_SUFFIX;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    @ServiceConnection
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    /** 전체 애플리케이션 컨텍스트가 DataSource 와 Flyway 를 요구하므로 함께 띄웁니다. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    KafkaTemplate<String, EventEnvelope> eventKafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestEventListener listener;

    @BeforeEach
    void resetListener() {
        listener.reset();
    }

    private EventEnvelope envelope(String type, UUID aggregateId, Map<String, Object> payload) {
        return new EventEnvelope(
            UUID.randomUUID(),
            type,
            1,
            Instant.parse("2026-08-11T03:00:00Z"),
            aggregateId,
            objectMapper.valueToTree(payload));
    }

    @Test
    @DisplayName("정상 이벤트 1건을 발행하면 같은 키와 payload로 수신한다")
    void publish_thenConsumeSameKeyAndPayload() throws Exception {
        UUID followId = UUID.randomUUID();
        UUID followerId = UUID.randomUUID();
        UUID followeeId = UUID.randomUUID();
        EventEnvelope sent = envelope(
            "follow.created", followId,
            Map.of("followerId", followerId.toString(), "followeeId", followeeId.toString()));

        // 파티션 키는 카탈로그 기준으로 호출부가 넘깁니다. follow.created 는 followId 입니다.
        eventKafkaTemplate.send(TOPIC, followId.toString(), sent).get();

        await().atMost(TIMEOUT).until(() -> !listener.received().isEmpty());

        ConsumerRecord<String, EventEnvelope> record = listener.received().get(0);
        assertThat(record.key()).isEqualTo(followId.toString());

        EventEnvelope got = record.value();
        assertThat(got.eventId()).isEqualTo(sent.eventId());
        assertThat(got.type()).isEqualTo("follow.created");
        assertThat(got.version()).isEqualTo(1);
        assertThat(got.aggregateId()).isEqualTo(followId);
        // occurredAt 이 UTC 로 왕복되는지 확인합니다. 실패하면 시간 직렬화 설정 문제입니다.
        assertThat(got.occurredAt()).isEqualTo(sent.occurredAt());
        assertThat(got.occurredAt().truncatedTo(ChronoUnit.SECONDS))
            .isEqualTo(Instant.parse("2026-08-11T03:00:00Z"));
        assertThat(got.payload().get("followerId").asText()).isEqualTo(followerId.toString());
        assertThat(got.payload().get("followeeId").asText()).isEqualTo(followeeId.toString());
    }

    @Test
    @DisplayName("역직렬화할 수 없는 메시지는 재시도 없이 DLT로 가고 컨테이너는 살아 있다")
    void malformedMessage_goesToDltWithoutRetry_andContainerSurvives() throws Exception {
        String key = "malformed-" + UUID.randomUUID();
        sendRawBytes(key, "not-a-json-envelope".getBytes());

        awaitDltRecordWithKey(key);

        // 역직렬화 실패는 리스너에 도달하지 않으므로 처리 시도가 없어야 합니다.
        assertThat(listener.attempts()).isZero();

        // 컨테이너가 살아 있으면 이어지는 정상 메시지가 계속 소비됩니다.
        UUID aggregateId = UUID.randomUUID();
        eventKafkaTemplate.send(TOPIC, aggregateId.toString(),
            envelope("follow.created", aggregateId, Map.of("followerId", "a", "followeeId", "b"))).get();

        await().atMost(TIMEOUT).until(() -> !listener.received().isEmpty());
        assertThat(listener.received()).hasSize(1);
    }

    @Test
    @DisplayName("일시적 처리 예외는 최초 1회와 재시도 3회 후 DLT로 간다")
    void retryableException_retriesThreeTimesThenDlt() throws Exception {
        listener.failWith(new IllegalStateException("일시적인 DB 오류"));

        UUID aggregateId = UUID.randomUUID();
        String key = aggregateId.toString();
        eventKafkaTemplate.send(TOPIC, key,
            envelope("follow.created", aggregateId, Map.of("followerId", "a", "followeeId", "b"))).get();

        // 1초, 2초, 4초 backoff 를 모두 지나야 재시도 횟수가 확정됩니다.
        await().atMost(TIMEOUT).until(() -> listener.attempts() >= 4);
        awaitDltRecordWithKey(key);

        assertThat(listener.attempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("계약 위반은 재시도하지 않고 즉시 DLT로 간다")
    void contractViolation_goesToDltWithoutRetry() throws Exception {
        listener.failWith(new EventContractViolationException("지원하지 않는 version"));

        UUID aggregateId = UUID.randomUUID();
        String key = aggregateId.toString();
        eventKafkaTemplate.send(TOPIC, key,
            envelope("follow.created", aggregateId, Map.of("followerId", "a", "followeeId", "b"))).get();

        awaitDltRecordWithKey(key);

        assertThat(listener.attempts()).isEqualTo(1);
    }

    /**
     * DLT 는 케이스 사이에 누적되므로 키로 대상 레코드를 특정합니다.
     *
     * <p>단순히 DLT 가 비어 있지 않은지만 보면 이전 케이스의 레코드 때문에 조건이 즉시
     * 충족되고, 재시도 backoff 가 끝나기 전에 단정이 실행됩니다.
     */
    private void awaitDltRecordWithKey(String key) {
        await().atMost(TIMEOUT).until(() -> readDlt().stream()
            .anyMatch(record -> key.equals(new String(record.key()))));
    }

    /** 공통 직렬화기를 우회해 깨진 원본 바이트를 그대로 넣습니다. */
    private void sendRawBytes(String key, byte[] value) throws Exception {
        Map<String, Object> props = Map.of(
            "bootstrap.servers", kafka.getBootstrapServers(),
            "acks", "all");
        DefaultKafkaProducerFactory<String, byte[]> factory = new DefaultKafkaProducerFactory<>(
            props, new StringSerializer(), new ByteArraySerializer());
        try {
            new KafkaTemplate<>(factory)
                .send(new ProducerRecord<>(TOPIC, key, value))
                .get();
        } finally {
            factory.destroy();
        }
    }

    private List<ConsumerRecord<byte[], byte[]>> readDlt() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
            kafka.getBootstrapServers(), "dlt-probe-" + UUID.randomUUID(), "true");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        props.put("auto.offset.reset", "earliest");

        List<ConsumerRecord<byte[], byte[]>> found = new java.util.ArrayList<>();
        try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<byte[], byte[]>(props)) {
            consumer.subscribe(List.of(DLT));
            consumer.poll(Duration.ofSeconds(2)).forEach(found::add);
        }
        return found;
    }

    @TestConfiguration
    static class TestListenerConfig {

        @Bean
        TestEventListener testEventListener() {
            return new TestEventListener();
        }
    }

    /**
     * 테스트 전용 소비자입니다.
     *
     * <p>Consumer Group 을 리스너가 직접 지정합니다. 공통 설정은 전역 group-id 를 두지
     * 않으므로, 이 지정이 빠지면 컨테이너가 시작되지 않습니다.
     */
    static class TestEventListener {

        private final List<ConsumerRecord<String, EventEnvelope>> received = new CopyOnWriteArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private volatile RuntimeException failure;

        @KafkaListener(
            topics = MoplTopics.FOLLOW_EVENTS,
            groupId = "mopl.test-consumer",
            containerFactory = "eventKafkaListenerContainerFactory")
        void onEvent(ConsumerRecord<String, EventEnvelope> record) {
            attempts.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            received.add(record);
        }

        void reset() {
            received.clear();
            attempts.set(0);
            failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
        }

        List<ConsumerRecord<String, EventEnvelope>> received() {
            return received;
        }

        int attempts() {
            return attempts.get();
        }
    }
}
