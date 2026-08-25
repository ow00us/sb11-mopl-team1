package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 리스너 중지 상태가 실제 Actuator 응답까지 이어지는지 검증합니다.
 *
 * <p>판정 규칙은 {@link KafkaListenerHealthIndicatorTest} 가 봅니다. 여기서 보는 것은 배선
 * 입니다. component 가 등록되어 전체 집계에 반영되는지, 그리고 그 DOWN 이 liveness 까지
 * 번지지는 않는지가 이 기능의 운영 정책이고, 그건 컨텍스트를 띄워야만 확인됩니다.
 *
 * <p>중지는 {@code stopAbnormally} 로 직접 만듭니다. DLT 발행을 실제로 반복 실패시키려면
 * backoff 를 여러 번 소진해야 해서 수십 초가 걸리고, 그 경로는
 * {@link DltFailureStoppingErrorHandlerTest} 가 따로 고정합니다.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
    "mopl.kafka.topic.auto-create=true",
    "mopl.kafka.listener.auto-startup=true",
    // 이 테스트는 Redis 를 띄우지 않습니다. 켜 두면 Redis 가 없다는 이유로 전체 집계가 계속
    // DOWN 이라, 리스너 상태가 집계에 반영되는지를 구분할 수 없습니다.
    "management.health.redis.enabled=false",
    // Elasticsearch 와 메일 서버도 이 테스트에서는 띄우지 않습니다. 같은 이유로 끕니다.
    "management.health.elasticsearch.enabled=false",
    "management.health.mail.enabled=false"
})
class KafkaListenerHealthIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Container
    @ServiceConnection
    static KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    KafkaListenerEndpointRegistry listenerEndpointRegistry;

    @Autowired
    KafkaListenerHealthIndicator healthIndicator;

    private MessageListenerContainer listener() {
        return listenerEndpointRegistry.getListenerContainers().iterator().next();
    }

    @BeforeEach
    void startListener() {
        MessageListenerContainer container = listener();
        if (!container.isRunning()) {
            container.start();
        }
        await().atMost(TIMEOUT).until(container::isRunning);
    }

    /** 다음 테스트가 정상 실행 상태에서 시작하도록 되돌립니다. */
    @AfterEach
    void restoreListener() {
        MessageListenerContainer container = listener();
        if (!container.isRunning()) {
            container.start();
        }
    }

    @Test
    @DisplayName("리스너가 실행 중이면 health가 UP이다")
    void health_up_whenListenerRunning() throws Exception {
        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);

        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * 소비가 멈춘 인스턴스가 계속 {@code UP} 으로 남으면 운영자는 로그를 뒤지기 전까지 그
     * 사실을 알 수 없습니다. 프로세스는 살아 있고 REST 는 정상 응답합니다.
     */
    @Test
    @DisplayName("리스너가 비정상 중지되면 전체 health가 DOWN이 된다")
    void health_down_whenListenerStoppedAbnormally() throws Exception {
        listener().stopAbnormally(() -> {
        });
        await().atMost(TIMEOUT).until(() -> !listener().isRunning());

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);

        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("DOWN"))
            // 상세에는 Consumer Group 과 토픽, 중지 사유가 들어갑니다. 이 경로는 인증 없이
            // 열려 있으므로 인증되지 않은 호출에는 상태만 보여야 합니다.
            .andExpect(jsonPath("$.components").doesNotExist());
    }

    /**
     * liveness 가 함께 내려가면 오케스트레이터가 프로세스를 재시작합니다. DLT 가 아직
     * 복구되지 않았다면 다시 띄운 리스너가 같은 이유로 또 멈춰, 원인은 그대로인 채 재시작만
     * 반복됩니다. readiness 도 마찬가지로 REST 를 처리할 수 있는 인스턴스를 빼 버립니다.
     */
    @Test
    @DisplayName("리스너가 멈춰도 liveness와 readiness는 UP을 유지한다")
    void probes_stayUp_whenListenerStopped() throws Exception {
        listener().stopAbnormally(() -> {
        });
        await().atMost(TIMEOUT).until(() -> !listener().isRunning());

        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("리스너를 다시 띄우면 health가 UP으로 돌아온다")
    void health_up_afterRestart() throws Exception {
        listener().stopAbnormally(() -> {
        });
        await().atMost(TIMEOUT).until(() -> !listener().isRunning());
        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.DOWN);

        listener().start();
        await().atMost(TIMEOUT).until(() -> listener().isRunning());

        assertThat(healthIndicator.health().getStatus()).isEqualTo(Status.UP);
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("리스너 실행 상태를 지표로 노출한다")
    void exposesListenerStateMetric() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andExpect(content().string(
                org.hamcrest.Matchers.containsString(
                    "mopl_kafka_listener_containers{state=\"running\"} 1")));
    }
}
