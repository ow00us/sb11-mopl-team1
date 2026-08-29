package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.MethodKafkaListenerEndpoint;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.messaging.handler.annotation.support.DefaultMessageHandlerMethodFactory;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 실제 Kafka 소비 루프가 handleRemaining을 통해 중지와 Actuator 집계까지 이어지는지 봅니다.
 * DLT 발행의 실패만 통제하고, 리스너 컨테이너나 stopAbnormally는 mock으로 대체하지 않습니다.
 */
@Testcontainers
class DltFailureStoppingContainerIntegrationTest {

    private static final String TOPIC = "listener-stop-regression";
    private static final String GROUP = "listener-stop-regression-group";
    private static final String LISTENER_ID = "listener-stop-regression-listener";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Test
    @DisplayName("실제 소비에서 DLT가 반복 실패하면 컨테이너가 중지되고 전체 health가 DOWN이다")
    void repeatedDltFailureStopsRealContainerAndChangesAggregateHealth() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                .all().get(10, TimeUnit.SECONDS);
        }

        AtomicInteger processingAttempts = new AtomicInteger();
        AtomicInteger dltAttempts = new AtomicInteger();
        AtomicReference<ConsumerRecord<?, ?>> failedRecord = new AtomicReference<>();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        KafkaListenerStopTracker tracker = new KafkaListenerStopTracker(meterRegistry);
        CountingDeadLetterRecoverer recoverer = new CountingDeadLetterRecoverer(
            (record, failure) -> {
                failedRecord.set(record);
                dltAttempts.incrementAndGet();
                throw new IllegalStateException("controlled DLT failure");
            }, meterRegistry);
        DltFailureStoppingErrorHandler errorHandler = new DltFailureStoppingErrorHandler(
            recoverer, tracker, new FixedBackOff(0L, 0L), 3);
        KafkaListenerEndpointRegistry registry = new KafkaListenerEndpointRegistry();
        DefaultKafkaProducerFactory<String, String> producerFactory =
            new DefaultKafkaProducerFactory<>(Map.of(
                "bootstrap.servers", kafka.getBootstrapServers(), "acks", "all"),
                new StringSerializer(), new StringSerializer());

        try {
            Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(
                kafka.getBootstrapServers(), GROUP, "false");
            consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(
                consumerProperties, new StringDeserializer(), new StringDeserializer()));
            factory.setCommonErrorHandler(errorHandler);
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
            factory.getContainerProperties().setPollTimeout(100L);
            factory.getContainerProperties().setShutdownTimeout(5_000L);

            DefaultMessageHandlerMethodFactory methodFactory =
                new DefaultMessageHandlerMethodFactory();
            methodFactory.afterPropertiesSet();
            MethodKafkaListenerEndpoint<String, String> endpoint = new MethodKafkaListenerEndpoint<>();
            endpoint.setId(LISTENER_ID);
            endpoint.setGroupId(GROUP);
            endpoint.setTopics(TOPIC);
            endpoint.setBean(new FailingListener(processingAttempts));
            endpoint.setMethod(FailingListener.class.getMethod("onMessage", ConsumerRecord.class));
            endpoint.setMessageHandlerMethodFactory(methodFactory);
            registry.registerListenerContainer(endpoint, factory, false);
            registry.start();
            MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
            assertThat(container).isNotNull();
            await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(container.getAssignedPartitions()).hasSize(1));

            KafkaListenerHealthIndicator indicator =
                new KafkaListenerHealthIndicator(registry, tracker);
            new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class))
                .withBean("kafkaListener", HealthIndicator.class, () -> indicator)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    HealthEndpoint healthEndpoint = context.getBean(HealthEndpoint.class);
                    assertThat(errorHandler.seeksAfterHandling()).isTrue();
                    assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);

                    new KafkaTemplate<>(producerFactory).send(TOPIC, "event-1", "payload")
                        .get(10, TimeUnit.SECONDS);

                    await().atMost(TIMEOUT).untilAsserted(() -> {
                        assertThat(container.isRunning()).isFalse();
                        assertThat(container.isInExpectedState()).isFalse();
                        assertThat(failedRecord.get()).isNotNull();
                        assertThat(recoverer.failureCount(failedRecord.get())).isZero();
                    });

                    assertThat(processingAttempts).hasValue(3);
                    assertThat(dltAttempts).hasValue(3);
                    assertThat(tracker.lastStop(GROUP)).hasValueSatisfying(stop -> {
                        assertThat(stop.topic()).isEqualTo(TOPIC);
                        assertThat(stop.reason()).contains("DLT 발행이 3회 연속 실패");
                    });
                    assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.DOWN);
                    assertThat(meterRegistry.get("mopl.kafka.listener.stops")
                        .tag("topic", TOPIC).counter().count()).isEqualTo(1);
                });
        } finally {
            registry.stop();
            registry.destroy();
            producerFactory.destroy();
            meterRegistry.close();
        }
    }

    static class FailingListener {

        private final AtomicInteger processingAttempts;

        FailingListener(AtomicInteger processingAttempts) {
            this.processingAttempts = processingAttempts;
        }

        public void onMessage(ConsumerRecord<String, String> record) {
            processingAttempts.incrementAndGet();
            throw new IllegalStateException("controlled processing failure");
        }
    }
}
