package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * DLT 조회와 수동 replay 를 실제 브로커로 검증합니다.
 *
 * <p>DLT 레코드는 공통 오류 처리가 남기는 헤더와 함께 직접 적재합니다. 리스너를 실패시켜
 * 만드는 방법은 재시도 backoff 를 모두 지나야 해서 느리고, 검증 대상은 실패를 만드는 과정이
 * 아니라 남은 레코드를 어떻게 다루는가입니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = "mopl.kafka.topic.auto-create=true")
class DeadLetterReplayIntegrationTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    /** 전체 애플리케이션 컨텍스트가 DataSource 와 Flyway 를 요구하므로 함께 띄웁니다. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final String TOPIC = MoplTopics.FOLLOW_EVENTS;
    private static final String DLT = MoplTopics.FOLLOW_EVENTS + MoplTopics.DLT_SUFFIX;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    /** 원본 토픽으로 다시 나온 레코드를 읽는 프로브입니다. */
    static KafkaConsumer<String, byte[]> probe;

    @Autowired
    DeadLetterReplayService deadLetterReplayService;

    @Autowired
    IdempotentEventProcessor idempotentEventProcessor;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    @Qualifier("replayKafkaTemplate")
    KafkaTemplate<String, byte[]> replayKafkaTemplate;

    @BeforeAll
    static void openProbe() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-replay-probe");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        probe = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer());
        probe.subscribe(List.of(TOPIC));
    }

    @AfterAll
    static void closeProbe() {
        if (probe != null) {
            probe.close();
        }
    }

    private EventEnvelope envelope(UUID eventId, UUID aggregateId) {
        return new EventEnvelope(
            eventId, "follow.created", 1, Instant.parse("2026-08-15T03:00:00Z"), aggregateId,
            objectMapper.valueToTree(Map.of("followerId", aggregateId.toString())));
    }

    /**
     * 공통 오류 처리가 남기는 형태로 DLT 레코드를 적재합니다.
     *
     * @return 적재된 레코드의 DLT 좌표
     */
    private DeadLetterCoordinate putInDeadLetterTopic(String key, byte[] value, String originalTopic) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(DLT, key, value);
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC,
            originalTopic.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN,
            "java.lang.IllegalStateException".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE,
            "일시적인 DB 오류".getBytes(StandardCharsets.UTF_8));

        try {
            var metadata = replayKafkaTemplate.send(record).get().getRecordMetadata();
            return new DeadLetterCoordinate(metadata.partition(), metadata.offset());
        } catch (Exception e) {
            throw new IllegalStateException("DLT 적재에 실패했습니다.", e);
        }
    }

    private record DeadLetterCoordinate(int partition, long offset) {
    }

    private byte[] bytesOf(EventEnvelope envelope) {
        try {
            return objectMapper.writeValueAsBytes(envelope);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 지정한 키의 레코드를 원본 토픽에서 찾을 때까지 읽습니다. */
    private List<ConsumerRecord<String, byte[]>> awaitRecordsWithKey(String key, int count) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        Instant deadline = Instant.now().plus(PROBE_TIMEOUT);

        while (Instant.now().isBefore(deadline) && found.size() < count) {
            ConsumerRecords<String, byte[]> records = probe.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : records) {
                if (key.equals(record.key())) {
                    found.add(record);
                }
            }
        }

        if (found.size() < count) {
            throw new AssertionError(
                "키가 %s 인 레코드를 %d건 받지 못했습니다. 실제 %d건".formatted(key, count, found.size()));
        }
        return found;
    }

    @Test
    @DisplayName("DLT 레코드의 원본 토픽, 키, eventId, 실패 원인과 적재 시각을 확인한다")
    void find_returnsFailureContext() {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String key = aggregateId.toString();
        putInDeadLetterTopic(key, bytesOf(envelope(eventId, aggregateId)), TOPIC);

        List<DeadLetterRecord> records = deadLetterReplayService.find(DLT, 100);

        DeadLetterRecord found = records.stream()
            .filter(record -> eventId.equals(record.eventId()))
            .findFirst()
            .orElseThrow();

        assertThat(found.originalTopic()).isEqualTo(TOPIC);
        assertThat(found.partitionKey()).isEqualTo(key);
        assertThat(found.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(found.exceptionMessage()).isEqualTo("일시적인 DB 오류");
        assertThat(found.enqueuedAt()).isNotNull();
        assertThat(found.deadLetterTopic()).isEqualTo(DLT);
    }

    /**
     * 값을 읽을 수 없는 레코드도 조회에 나와야 합니다.
     *
     * <p>DLT 로 오는 이유 중 하나가 역직렬화 실패입니다. 계약 타입으로 읽으면 정작 확인해야
     * 할 레코드가 조회에서 사라집니다.
     */
    @Test
    @DisplayName("값을 읽을 수 없는 레코드도 조회된다")
    void find_includesUnreadableValue() {
        String key = "broken-" + UUID.randomUUID();
        putInDeadLetterTopic(key, "not-a-json-envelope".getBytes(StandardCharsets.UTF_8), TOPIC);

        List<DeadLetterRecord> records = deadLetterReplayService.find(DLT, 100);

        DeadLetterRecord found = records.stream()
            .filter(record -> key.equals(record.partitionKey()))
            .findFirst()
            .orElseThrow();

        assertThat(found.eventId()).isNull();
        assertThat(found.originalTopic()).isEqualTo(TOPIC);
    }

    @Test
    @DisplayName("지목한 레코드를 같은 eventId와 키로 원본 토픽에 다시 보낸다")
    void replay_keepsEventIdAndKey() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String key = aggregateId.toString();
        DeadLetterCoordinate coordinate =
            putInDeadLetterTopic(key, bytesOf(envelope(eventId, aggregateId)), TOPIC);

        DeadLetterRecord replayed =
            deadLetterReplayService.replay(DLT, coordinate.partition(), coordinate.offset());

        assertThat(replayed.eventId()).isEqualTo(eventId);
        assertThat(replayed.originalTopic()).isEqualTo(TOPIC);

        ConsumerRecord<String, byte[]> record = awaitRecordsWithKey(key, 1).get(0);
        EventEnvelope got = objectMapper.readValue(record.value(), EventEnvelope.class);

        assertThat(record.key()).isEqualTo(key);
        assertThat(got.eventId()).isEqualTo(eventId);
        assertThat(got.type()).isEqualTo("follow.created");
        assertThat(got.occurredAt()).isEqualTo(Instant.parse("2026-08-15T03:00:00Z"));
    }

    /**
     * 지목하지 않은 레코드는 나가지 않아야 합니다.
     *
     * <p>DLT 전체를 한 번에 다시 보내면 원인을 확인하지 않은 레코드까지 함께 나가 같은 실패가
     * 반복됩니다.
     */
    @Test
    @DisplayName("좌표에 레코드가 없으면 아무 것도 보내지 않는다")
    void replay_rejectsMissingRecord() {
        long startedAt = System.nanoTime();

        assertThatThrownBy(() -> deadLetterReplayService.replay(DLT, 0, Long.MAX_VALUE - 1))
            .isInstanceOf(IllegalArgumentException.class);

        // 범위를 먼저 확인하므로 읽기 한도를 기다리지 않고 끝나야 합니다. 기다린다면 범위 밖
        // seek 이 auto.offset.reset 으로 되돌려져 엉뚱한 레코드를 훑고 있다는 뜻입니다.
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
            .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("공통 계약의 DLT가 아니면 조회도 replay도 거부한다")
    void rejectsTopicOutsideContract() {
        assertThatThrownBy(() -> deadLetterReplayService.find("random.topic.DLT", 10))
            .isInstanceOf(EventContractViolationException.class);
        assertThatThrownBy(() -> deadLetterReplayService.find(TOPIC, 10))
            .isInstanceOf(EventContractViolationException.class);
        assertThatThrownBy(() -> deadLetterReplayService.replay("random.topic.DLT", 0, 0))
            .isInstanceOf(EventContractViolationException.class);
    }

    /**
     * 원본 토픽은 헤더에서 읽습니다. 헤더는 브로커에 저장된 데이터이므로 그대로 믿고 발행하면
     * 공통 계약 밖의 토픽으로 나갈 수 있습니다.
     */
    @Test
    @DisplayName("원본 토픽 헤더가 계약 밖을 가리키면 발행하지 않는다")
    void replay_rejectsOriginalTopicOutsideContract() {
        UUID aggregateId = UUID.randomUUID();
        DeadLetterCoordinate coordinate = putInDeadLetterTopic(
            aggregateId.toString(),
            bytesOf(envelope(UUID.randomUUID(), aggregateId)),
            "attacker.topic");

        assertThatThrownBy(() ->
            deadLetterReplayService.replay(DLT, coordinate.partition(), coordinate.offset()))
            .isInstanceOf(EventContractViolationException.class);
    }

    /**
     * 같은 레코드를 두 번 보내도 소비 부수 효과는 한 번이어야 합니다.
     *
     * <p>replay 는 eventId 를 유지하므로 소비자의 멱등 경계가 그대로 동작합니다. 새 eventId 를
     * 붙여 보내면 이 성질이 깨져 중복 처리로 이어집니다.
     */
    @Test
    @DisplayName("같은 레코드를 두 번 replay해도 소비자는 한 번만 처리한다")
    void repeatedReplay_doesNotBreakConsumerIdempotency() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID aggregateId = UUID.randomUUID();
        String key = aggregateId.toString();
        DeadLetterCoordinate coordinate =
            putInDeadLetterTopic(key, bytesOf(envelope(eventId, aggregateId)), TOPIC);

        deadLetterReplayService.replay(DLT, coordinate.partition(), coordinate.offset());
        deadLetterReplayService.replay(DLT, coordinate.partition(), coordinate.offset());

        List<ConsumerRecord<String, byte[]>> records = awaitRecordsWithKey(key, 2);
        assertThat(records).hasSize(2);

        AtomicInteger handled = new AtomicInteger();
        String consumerName = "mopl.test.replay-" + UUID.randomUUID();
        for (ConsumerRecord<String, byte[]> record : records) {
            EventEnvelope got = objectMapper.readValue(record.value(), EventEnvelope.class);
            idempotentEventProcessor.process(consumerName, got, handled::incrementAndGet);
        }

        assertThat(handled.get()).isEqualTo(1);
    }
}
