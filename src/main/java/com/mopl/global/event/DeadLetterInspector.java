package com.mopl.global.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * DLT 에 보존된 소비 실패 레코드를 읽습니다.
 *
 * <p>값을 {@code byte[]} 로 읽습니다. DLT 로 오는 이유 중 하나가 역직렬화 실패라, 계약
 * 타입으로 읽으면 정작 확인해야 할 레코드를 읽지 못합니다.
 *
 * <p>offset 을 커밋하지 않습니다. 조회는 DLT 의 보존 상태를 바꾸지 않아야 하고, 커밋하면
 * 같은 그룹으로 다시 조회할 때 앞서 본 레코드가 사라집니다.
 *
 * <p>리스너 컨테이너를 쓰지 않고 조회 때마다 consumer 를 만들어 씁니다. DLT 조회는 사람이
 * 필요할 때 하는 일이라 상시 연결을 유지할 이유가 없습니다.
 */
@Slf4j
@Component
public class DeadLetterInspector {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);

    /** 진행이 없을 때 조회를 멈추는 한도입니다. 없으면 빈 DLT 조회가 끝나지 않습니다. */
    private static final Duration READ_DEADLINE = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final Supplier<Consumer<String, byte[]>> consumerFactory;
    private final Clock clock;

    @Autowired
    public DeadLetterInspector(
        KafkaProperties kafkaProperties,
        KafkaConnectionDetails connectionDetails,
        ObjectMapper objectMapper
    ) {
        this(objectMapper, () -> createConsumer(kafkaProperties, connectionDetails), Clock.systemUTC());
    }

    DeadLetterInspector(
        ObjectMapper objectMapper,
        Supplier<Consumer<String, byte[]>> consumerFactory,
        Clock clock
    ) {
        this.objectMapper = objectMapper;
        this.consumerFactory = consumerFactory;
        this.clock = clock;
    }

    /**
     * DLT 의 레코드를 오래된 순으로 조회합니다.
     *
     * @param deadLetterTopic 조회할 DLT 이름. 공통 계약의 DLT 만 허용합니다.
     * @param limit           최대 건수
     */
    public List<DeadLetterRecord> find(String deadLetterTopic, int limit) {
        MoplTopics.requireDeadLetterTopic(deadLetterTopic);
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 은 1 이상이어야 합니다. 실제 " + limit);
        }

        List<DeadLetterRecord> found = new ArrayList<>();
        try (Consumer<String, byte[]> consumer = consumerFactory.get()) {
            List<TopicPartition> partitions = partitionsOf(consumer, deadLetterTopic);
            if (partitions.isEmpty()) {
                return List.of();
            }

            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            Instant deadline = clock.instant().plus(READ_DEADLINE);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

            while (found.size() < limit
                && clock.instant().isBefore(deadline)
                && hasRemaining(consumer, endOffsets)) {

                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, byte[]> record : records) {
                    found.add(toDeadLetterRecord(record));
                    if (found.size() == limit) {
                        break;
                    }
                }
            }
        }
        return found;
    }

    /**
     * DLT 의 한 좌표에 있는 레코드를 원본 바이트째로 읽습니다.
     *
     * <p>replay 는 값을 다시 만들지 않고 이 바이트를 그대로 발행합니다. 다시 만들면 소비자가
     * 처음 받은 것과 다른 메시지가 되어 실패 재현이 성립하지 않습니다.
     *
     * <p>범위를 먼저 확인하고 seek 합니다. 범위 밖으로 seek 하면 consumer 가
     * {@code auto.offset.reset} 정책에 따라 위치를 옮기므로, 지목한 것과 다른 레코드를 읽게
     * 됩니다. 지목 대상이 없다는 사실은 그 전에 확정해야 합니다.
     */
    public Optional<ConsumerRecord<String, byte[]>> findRawAt(
        String deadLetterTopic, int partition, long offset
    ) {
        MoplTopics.requireDeadLetterTopic(deadLetterTopic);
        if (offset < 0) {
            throw new IllegalArgumentException("offset 은 0 이상이어야 합니다. 실제 " + offset);
        }

        TopicPartition target = new TopicPartition(deadLetterTopic, partition);
        try (Consumer<String, byte[]> consumer = consumerFactory.get()) {
            consumer.assign(List.of(target));

            long endOffset = consumer.endOffsets(List.of(target)).getOrDefault(target, 0L);
            long beginningOffset = consumer.beginningOffsets(List.of(target)).getOrDefault(target, 0L);
            if (offset >= endOffset || offset < beginningOffset) {
                // 아직 쓰이지 않았거나 보존 기간이 지나 삭제된 좌표입니다.
                return Optional.empty();
            }

            consumer.seek(target, offset);

            Instant deadline = clock.instant().plus(READ_DEADLINE);
            while (clock.instant().isBefore(deadline)) {
                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, byte[]> record : records.records(target)) {
                    if (record.offset() == offset) {
                        return Optional.of(record);
                    }
                    // seek 한 지점부터 읽으므로 앞선 offset 은 나오지 않습니다. 지나쳤다면
                    // 그 좌표는 압축이나 트랜잭션 표식으로 비어 있는 자리입니다.
                    if (record.offset() > offset) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 레코드에서 운영자가 보는 값을 뽑아냅니다. */
    public DeadLetterRecord toDeadLetterRecord(ConsumerRecord<String, byte[]> record) {
        return new DeadLetterRecord(
            record.topic(),
            record.partition(),
            record.offset(),
            Instant.ofEpochMilli(record.timestamp()),
            originalTopicOf(record),
            record.key(),
            eventIdOf(record),
            header(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
            header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
    }

    /**
     * 실패한 원본 토픽입니다.
     *
     * <p>공통 오류 처리가 남긴 헤더를 먼저 봅니다. 헤더가 없으면 DLT 이름에서 접미사를 떼어
     * 씁니다. 직접 적재한 레코드에는 헤더가 없을 수 있습니다.
     */
    private String originalTopicOf(ConsumerRecord<String, byte[]> record) {
        String fromHeader = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        return fromHeader != null ? fromHeader : MoplTopics.originalTopicOf(record.topic());
    }

    /**
     * envelope 의 eventId 입니다. 읽지 못하면 {@code null} 입니다.
     *
     * <p>값이 깨져 있어도 조회 자체는 성공해야 합니다. 깨진 레코드를 확인하는 것이 이 조회의
     * 목적 중 하나입니다.
     */
    private UUID eventIdOf(ConsumerRecord<String, byte[]> record) {
        if (record.value() == null) {
            return null;
        }
        try {
            JsonNode eventId = objectMapper.readTree(record.value()).get("eventId");
            return eventId == null || !eventId.isTextual() ? null : UUID.fromString(eventId.asText());
        } catch (Exception e) {
            log.debug("DLT 레코드에서 eventId 를 읽지 못했습니다. topic={}, offset={}",
                record.topic(), record.offset(), e);
            return null;
        }
    }

    private String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private List<TopicPartition> partitionsOf(Consumer<String, byte[]> consumer, String topic) {
        List<PartitionInfo> infos = consumer.partitionsFor(topic);
        if (infos == null) {
            return List.of();
        }
        return infos.stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
    }

    private boolean hasRemaining(Consumer<String, byte[]> consumer, Map<TopicPartition, Long> endOffsets) {
        return endOffsets.entrySet().stream()
            .anyMatch(entry -> consumer.position(entry.getKey()) < entry.getValue());
    }

    /**
     * 조회 전용 consumer 입니다.
     *
     * <p>그룹을 매번 새로 만들고 자동 커밋을 끕니다. 도메인 리스너와 그룹을 공유하면 조회가
     * 그 그룹의 offset 을 건드려 정상 소비에 영향을 줍니다.
     */
    private static Consumer<String, byte[]> createConsumer(
        KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails
    ) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "mopl.dlt.inspect-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer());
    }
}
