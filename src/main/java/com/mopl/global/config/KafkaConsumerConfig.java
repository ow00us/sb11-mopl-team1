package com.mopl.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.CountingDeadLetterRecoverer;
import com.mopl.global.event.DltFailureStoppingErrorHandler;
import com.mopl.global.event.EventContractViolationException;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.MoplTopics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.messaging.converter.MessageConversionException;

/**
 * 모든 도메인이 공유하는 Kafka Consumer 설정입니다.
 *
 * <p>Consumer Group 은 여기서 정하지 않습니다. 전역 기본 group-id 를 두면 도메인
 * 리스너가 groupId 를 빠뜨렸을 때 서로 다른 소비 목적이 조용히 같은 Group 을
 * 공유합니다. 각 리스너가 {@code @KafkaListener(groupId = "mopl.notification")} 처럼
 * 소비 목적을 명시해야 합니다.
 */
@Configuration
public class KafkaConsumerConfig {

    /** DLT 발행이 같은 레코드에서 이 횟수만큼 연속 실패하면 컨테이너를 중지합니다. */
    private static final int MAX_CONSECUTIVE_DLT_FAILURES = 3;

    private final KafkaProperties kafkaProperties;
    private final KafkaConnectionDetails connectionDetails;
    private final ObjectMapper objectMapper;

    /**
     * 리스너 컨테이너 자동 시작 여부입니다.
     *
     * <p>로컬에 Kafka 를 띄우지 않고 다른 기능을 개발할 때 연결 경고가 반복되는 것을
     * 막기 위한 스위치입니다.
     */
    private final boolean listenerAutoStartup;

    public KafkaConsumerConfig(
        KafkaProperties kafkaProperties,
        KafkaConnectionDetails connectionDetails,
        ObjectMapper objectMapper,
        @Value("${mopl.kafka.listener.auto-startup:true}") boolean listenerAutoStartup
    ) {
        this.kafkaProperties = kafkaProperties;
        this.connectionDetails = connectionDetails;
        this.objectMapper = objectMapper;
        this.listenerAutoStartup = listenerAutoStartup;
    }

    @Bean
    public ConsumerFactory<String, EventEnvelope> eventConsumerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));

        // 연결 주소는 KafkaConnectionDetails 에서 가져옵니다. 속성 파일 값만 쓰면
        // Testcontainers 의 @ServiceConnection 같은 외부 주입이 반영되지 않습니다.
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getBootstrapServers());

        // 타입 헤더를 신뢰하지 않고 EventEnvelope 로만 읽습니다.
        // 대상 타입을 EventEnvelope 로 고정하고 헤더를 쓰지 않으므로 신뢰 패키지 설정은
        // 관여하지 않습니다.
        JsonDeserializer<EventEnvelope> delegate =
            new JsonDeserializer<>(EventEnvelope.class, objectMapper, false);

        // 역직렬화 실패를 리스너 예외가 아니라 DeserializationException 으로 표면화해
        // 재시도 없이 DLT 로 보낼 수 있게 합니다.
        ErrorHandlingDeserializer<EventEnvelope> valueDeserializer =
            new ErrorHandlingDeserializer<>(delegate);

        return new DefaultKafkaConsumerFactory<>(
            props, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, EventEnvelope> eventKafkaListenerContainerFactory(
        DefaultErrorHandler eventErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, EventEnvelope> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(eventConsumerFactory());
        factory.setCommonErrorHandler(eventErrorHandler);
        factory.setAutoStartup(listenerAutoStartup);

        // 레코드 하나의 처리가 끝난 뒤 offset 을 커밋합니다. 소비 결과의 DB 트랜잭션이
        // 성공한 뒤에만 offset 이 완료되도록 하려면 배치 커밋이 아니어야 합니다.
        factory.getContainerProperties().setAckMode(AckMode.RECORD);
        return factory;
    }

    /**
     * 재시도 대상과 재시도하지 않을 오류를 구분하는 공통 오류 처리입니다.
     *
     * <p>일시적인 오류는 1초, 2초, 4초 backoff 로 최대 3회 재시도한 뒤 DLT 로 보냅니다.
     * 같은 메시지를 다시 처리해도 결과가 같은 오류는 재시도하지 않습니다.
     */
    @Bean
    public DefaultErrorHandler eventErrorHandler(
        @Qualifier("deadLetterKafkaTemplate") KafkaTemplate<String, Object> deadLetterKafkaTemplate,
        MeterRegistry meterRegistry
    ) {
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(
            deadLetterKafkaTemplate,
            // 파티션을 -1 로 두어 DLT 의 파티션 수가 원본과 달라도 문제가 없게 합니다.
            (record, exception) ->
                new TopicPartition(MoplTopics.deadLetterTopicOf(record.topic()), -1));

        // 발행 실패를 예외로 전파해야 아래 실패 카운트와 컨테이너 중지 정책이 동작합니다.
        // 현재 버전의 기본값도 true 지만, 계약에 걸린 동작을 라이브러리 기본값에 맡기지
        // 않기 위해 명시합니다. 결과 대기 시간은 DLT producer 의 delivery.timeout.ms
        // 에서 파생되므로 별도로 두지 않습니다.
        publisher.setFailIfSendResultIsError(true);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);

        DefaultErrorHandler errorHandler = new DltFailureStoppingErrorHandler(
            new CountingDeadLetterRecoverer(publisher, meterRegistry), backOff, MAX_CONSECUTIVE_DLT_FAILURES);
        errorHandler.addNotRetryableExceptions(
            DeserializationException.class,
            MessageConversionException.class,
            EventContractViolationException.class);
        return errorHandler;
    }

}
