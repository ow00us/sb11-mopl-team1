package com.mopl.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * 모든 도메인이 공유하는 Kafka Producer 설정입니다.
 *
 * <p>도메인은 직렬화기와 연결 설정을 다시 정의하지 않고 여기서 등록한
 * {@link KafkaTemplate} 을 주입받습니다.
 *
 * <p><b>주의</b>: Outbox 적용 이벤트의 생산 도메인은 이 템플릿을 직접 호출하지
 * 않습니다. 도메인 상태 변경과 Outbox 기록을 같은 트랜잭션에 넣고, 커밋된 Outbox 를
 * relay 가 발행합니다. 이 템플릿의 정상 호출자는 relay 와 인프라 검증 테스트입니다.
 */
@Configuration
public class KafkaProducerConfig {

    private final KafkaProperties kafkaProperties;
    private final KafkaConnectionDetails connectionDetails;
    private final ObjectMapper objectMapper;

    public KafkaProducerConfig(
        KafkaProperties kafkaProperties,
        KafkaConnectionDetails connectionDetails,
        ObjectMapper objectMapper
    ) {
        this.kafkaProperties = kafkaProperties;
        this.connectionDetails = connectionDetails;
        this.objectMapper = objectMapper;
    }

    /**
     * 신뢰성 속성을 공통으로 고정합니다.
     *
     * <p>멱등 producer 를 켜야 재시도 시 파티션 내 순서가 유지됩니다. 순서 보장 자체는
     * Outbox relay 가 담당하지만, 그 앞단의 재시도가 순서를 뒤집으면 의미가 없습니다.
     */
    private Map<String, Object> baseProducerProperties() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));

        // 연결 주소는 KafkaProperties 가 아니라 KafkaConnectionDetails 에서 가져옵니다.
        // Boot 의 자동 구성이 쓰는 경로이며, Testcontainers 의 @ServiceConnection 처럼
        // 속성 파일 밖에서 주소를 주입하는 방식이 여기서만 반영됩니다.
        List<String> bootstrapServers = connectionDetails.getBootstrapServers();
        requireResolved(bootstrapServers);
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return props;
    }

    /**
     * 해석되지 않은 플레이스홀더가 그대로 넘어오는 것을 막습니다.
     *
     * <p>{@code @ConfigurationProperties} 바인딩은 해석하지 못한 플레이스홀더를 예외 없이
     * 문자열 그대로 남깁니다. 그래서 prod 의 {@code ${KAFKA_BOOTSTRAP_SERVERS}} 가 비어
     * 있어도 기동이 실패하지 않고, 한참 뒤에 kafka-clients 의
     * {@code Invalid url in bootstrap.servers} 로 드러납니다. 원인을 찾기 어려우므로
     * 여기서 무엇이 빠졌는지 알려주고 멈춥니다.
     */
    private void requireResolved(List<String> bootstrapServers) {
        // 빈 문자열이나 공백 항목도 거부합니다. 목록이 비어 있지 않다는 것만 보면
        // KAFKA_BOOTSTRAP_SERVERS="a,,b" 같은 값이 통과해 Producer 생성이나 첫 발행에서
        // 뒤늦게 실패합니다.
        boolean invalid = bootstrapServers.isEmpty()
            || bootstrapServers.stream()
                .anyMatch(server -> server == null || server.isBlank() || server.contains("${"));

        if (invalid) {
            throw new IllegalStateException(
                "Kafka bootstrap 주소가 올바르지 않습니다. KAFKA_BOOTSTRAP_SERVERS 를 지정하세요. 현재 값: "
                    + bootstrapServers);
        }
    }

    @Bean
    public ProducerFactory<String, EventEnvelope> eventProducerFactory() {
        // 타입 헤더를 붙이지 않습니다. 계약의 type 필드가 유일한 타입 정보입니다.
        JsonSerializer<EventEnvelope> valueSerializer = new JsonSerializer<>(objectMapper);
        valueSerializer.setAddTypeInfo(false);
        return new DefaultKafkaProducerFactory<>(
            baseProducerProperties(), new StringSerializer(), valueSerializer);
    }

    /**
     * 도메인 이벤트 발행에 사용하는 공통 템플릿입니다.
     *
     * <p>파티션 키는 호출부가 이벤트 카탈로그 기준으로 직접 넘깁니다. 공통 계층이 키를
     * 추론하면 카탈로그와 코드가 두 곳에서 갈라집니다.
     */
    @Bean
    public KafkaTemplate<String, EventEnvelope> eventKafkaTemplate() {
        return new KafkaTemplate<>(eventProducerFactory());
    }

    /**
     * DLT 발행 전용 템플릿입니다.
     *
     * <p>DLT 로 가는 값은 두 종류입니다. 처리 예외라면 역직렬화까지 성공한
     * {@link EventEnvelope} 이고, 역직렬화 실패라면 원본 byte[] 입니다. 하나의 직렬화기로는
     * 둘을 모두 보존할 수 없으므로 값의 실제 타입에 따라 위임합니다.
     *
     * <p>키는 항상 String 입니다. 값 역직렬화만 실패한 경우 키는 정상적으로 String 으로
     * 읽히므로, 키까지 byte[] 로 가정하면 DLT 발행이 직렬화 오류로 실패합니다.
     */
    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate() {
        JsonSerializer<Object> jsonSerializer = new JsonSerializer<>(objectMapper);
        jsonSerializer.setAddTypeInfo(false);

        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, jsonSerializer);

        Map<String, Object> props = baseProducerProperties();

        // DLT 발행은 마지막 수단이므로 실패를 빨리 드러내야 합니다. 기본값(전달 2분,
        // 요청 30초)이면 연속 실패 판정까지 수 분이 걸리고 그동안 파티션이 멈춥니다.
        // 짧게 잡아 브로커가 응답하지 않는 상황이 곧 컨테이너 중지로 이어지게 합니다.
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);

        ProducerFactory<String, Object> factory = new DefaultKafkaProducerFactory<>(
            props,
            new StringSerializer(),
            new DelegatingByTypeSerializer(delegates, true));
        return new KafkaTemplate<>(factory);
    }
}
