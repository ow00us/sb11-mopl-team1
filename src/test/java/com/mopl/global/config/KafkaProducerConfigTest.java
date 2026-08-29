package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.ProducerFactory;

/** Factory와 serializer만 검사하며 createProducer/send를 호출하거나 broker에 연결하지 않습니다. */
class KafkaProducerConfigTest {

    private static final List<String> CONNECTION_BOOTSTRAP =
        List.of("service-connection.example.test:19092", "service-connection.example.test:19093");
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private KafkaProducerConfig config(List<String> bootstrapServers) {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("properties.example.test:9092"));
        properties.getProducer().setAcks("0");
        properties.getProducer().getProperties().put("enable.idempotence", "false");
        properties.getProducer().getProperties().put("max.in.flight.requests.per.connection", "1");
        properties.getProducer().getProperties().put("request.timeout.ms", "25000");
        properties.getProducer().getProperties().put("delivery.timeout.ms", "45000");

        KafkaConnectionDetails details = mock(KafkaConnectionDetails.class);
        when(details.getBootstrapServers()).thenReturn(bootstrapServers);
        return new KafkaProducerConfig(properties, details, objectMapper);
    }

    private static Stream<Arguments> invalidBootstrapServers() {
        return Stream.of(
            Arguments.of("empty list", List.<String>of()),
            Arguments.of("null entry", Arrays.asList("valid.example.test:9092", null)),
            Arguments.of("empty entry", List.of("valid.example.test:9092", "")),
            Arguments.of("blank entry", List.of("valid.example.test:9092", " \t")),
            Arguments.of("unresolved entry", List.of("valid.example.test:9092", "${TEST_BROKER}")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBootstrapServers")
    @DisplayName("bootstrap 항목이 잘못되면 event·DLT·replay factory 생성에서 즉시 거부한다")
    void rejectsInvalidBootstrapBeforeProducerCreation(String scenario, List<String> bootstrap) {
        KafkaProducerConfig config = config(bootstrap);

        assertThatThrownBy(config::eventProducerFactory)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("KAFKA_BOOTSTRAP_SERVERS");
        assertThatThrownBy(config::deadLetterKafkaTemplate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("KAFKA_BOOTSTRAP_SERVERS");
        assertThatThrownBy(config::replayKafkaTemplate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("KAFKA_BOOTSTRAP_SERVERS");
    }

    @Test
    @DisplayName("모든 factory는 ConnectionDetails 주소를 우선하고 신뢰성 설정을 강제한다")
    void allFactoriesPreferConnectionDetailsAndEnforceReliability() {
        KafkaProducerConfig config = config(CONNECTION_BOOTSTRAP);
        List<ProducerFactory<String, ?>> factories = List.of(
            config.eventKafkaTemplate().getProducerFactory(),
            config.deadLetterKafkaTemplate().getProducerFactory(),
            config.replayKafkaTemplate().getProducerFactory());

        for (ProducerFactory<String, ?> factory : factories) {
            assertThat(factory.getConfigurationProperties())
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, CONNECTION_BOOTSTRAP)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
            assertThat(factory.getKeySerializer()).isInstanceOf(StringSerializer.class);
        }
    }

    @Test
    @DisplayName("이벤트는 JSON으로 직렬화하며 Java 타입 헤더를 계약에 추가하지 않는다")
    void eventSerializerPreservesEnvelopeWithoutJavaTypeHeader() throws Exception {
        ProducerFactory<String, EventEnvelope> factory = config(CONNECTION_BOOTSTRAP)
            .eventKafkaTemplate().getProducerFactory();
        EventEnvelope event = envelope();
        RecordHeaders headers = new RecordHeaders();

        byte[] value = factory.getValueSerializer().serialize("event-test", headers, event);

        assertThat(objectMapper.readValue(value, EventEnvelope.class)).isEqualTo(event);
        assertThat(headers.lastHeader("__TypeId__")).isNull();
        assertThat(factory.getKeySerializer().serialize("event-test", "partition-key"))
            .isEqualTo("partition-key".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("replay는 깨진 JSON도 원본 바이트 그대로 보내고 일반 전달 제한을 유지한다")
    void replayPreservesRawBytesAndConfiguredTimeouts() {
        ProducerFactory<String, byte[]> factory = config(CONNECTION_BOOTSTRAP)
            .replayKafkaTemplate().getProducerFactory();
        byte[] original = new byte[]{0, (byte) 0xff, '{', 'b', 'r', 'o', 'k', 'e', 'n'};

        assertThat(factory.getValueSerializer()).isInstanceOf(ByteArraySerializer.class);
        assertThat(factory.getValueSerializer().serialize("replay-test", original))
            .isEqualTo(original);
        assertThat(factory.getConfigurationProperties())
            .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "25000")
            .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "45000");
    }

    @Test
    @DisplayName("DLT는 역직렬화 실패의 원본 바이트와 처리 실패의 이벤트를 각각 보존한다")
    void deadLetterSerializerHandlesBytesAndEnvelopes() throws Exception {
        ProducerFactory<String, Object> factory = config(CONNECTION_BOOTSTRAP)
            .deadLetterKafkaTemplate().getProducerFactory();
        byte[] original = new byte[]{(byte) 0xff, 0, '{'};
        RecordHeaders rawHeaders = new RecordHeaders();
        RecordHeaders eventHeaders = new RecordHeaders();
        EventEnvelope event = envelope();

        assertThat(factory.getValueSerializer().serialize("dlt-test", rawHeaders, original))
            .isEqualTo(original);
        byte[] serializedEvent = factory.getValueSerializer()
            .serialize("dlt-test", eventHeaders, event);

        assertThat(objectMapper.readValue(serializedEvent, EventEnvelope.class)).isEqualTo(event);
        assertThat(rawHeaders.lastHeader("__TypeId__")).isNull();
        assertThat(eventHeaders.lastHeader("__TypeId__")).isNull();
        assertThat(factory.getConfigurationProperties())
            .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000)
            .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
    }

    private EventEnvelope envelope() {
        return new EventEnvelope(
            UUID.fromString("a461dcbc-1982-4e8b-86a1-3b985dad0ca5"),
            "follow.created", 1, Instant.parse("2026-08-29T00:00:00Z"),
            UUID.fromString("6de5e778-ef62-473c-8adb-4ce2f32b13e7"),
            objectMapper.valueToTree(Map.of("followeeId", "test-followee")));
    }
}
