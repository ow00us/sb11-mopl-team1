package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * 리스너 중지 상태가 health 로 드러나는지 검증합니다.
 *
 * <p>컨테이너를 실제로 띄우지 않고 상태 조합만 확인합니다. 판정 규칙 자체가 이 클래스의
 * 전부이고, 규칙이 틀리면 브로커를 띄워도 틀립니다.
 */
class KafkaListenerHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-08-21T03:00:00Z");
    private static final String GROUP = "mopl.notification";

    private final KafkaListenerEndpointRegistry listenerEndpointRegistry =
        mock(KafkaListenerEndpointRegistry.class);
    private final KafkaListenerStopTracker stopTracker = new KafkaListenerStopTracker(
        new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneId.of("UTC")));
    private final KafkaListenerHealthIndicator indicator =
        new KafkaListenerHealthIndicator(listenerEndpointRegistry, stopTracker);

    /**
     * 컨테이너를 흉내 냅니다.
     *
     * <p>{@code inExpectedState} 가 판정 기준입니다. spring-kafka 는 오류 처리가 멈춘
     * 컨테이너에만 이 값을 거짓으로 둡니다.
     */
    private MessageListenerContainer container(
        String listenerId, boolean running, boolean inExpectedState
    ) {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.getListenerId()).thenReturn(listenerId);
        when(container.getGroupId()).thenReturn(GROUP);
        when(container.isRunning()).thenReturn(running);
        when(container.isInExpectedState()).thenReturn(inExpectedState);
        when(container.getContainerProperties())
            .thenReturn(new ContainerProperties("mopl.follow.events"));
        return container;
    }

    private void register(MessageListenerContainer... containers) {
        when(listenerEndpointRegistry.getListenerContainers()).thenReturn(List.of(containers));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> listenerDetail(Health health, String listenerId) {
        Map<String, Object> listeners =
            (Map<String, Object>) health.getDetails().get("listeners");
        return (Map<String, Object>) listeners.get(listenerId);
    }

    @Test
    @DisplayName("리스너가 모두 정상 실행이면 UP을 보고한다")
    void health_up_whenAllContainersRunning() {
        register(container("listener-0", true, true));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("containers", 1);
        assertThat(listenerDetail(health, "listener-0"))
            .containsEntry("running", true)
            .containsEntry("stoppedAbnormally", false)
            .containsEntry("groupId", GROUP)
            .containsEntry("topics", List.of("mopl.follow.events"));
    }

    /**
     * 이 판정이 없으면 소비가 멈춘 인스턴스가 계속 {@code UP} 으로 남습니다. 프로세스는 살아
     * 있고 REST 는 정상 응답하므로 다른 어떤 지표에도 흔적이 없습니다.
     */
    @Test
    @DisplayName("비정상 중지된 리스너가 있으면 DOWN을 보고한다")
    void health_down_whenContainerStoppedAbnormally() {
        register(container("listener-0", false, false));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(listenerDetail(health, "listener-0"))
            .containsEntry("running", false)
            .containsEntry("stoppedAbnormally", true);
    }

    @Test
    @DisplayName("중지 사유를 기록해 두면 상세에 토픽과 원인을 함께 보여준다")
    void health_down_includesRecordedReason() {
        stopTracker.recordDeadLetterStop(
            GROUP, "listener-0", "mopl.follow.events", "DLT 발행이 3회 연속 실패했습니다.");
        register(container("listener-0", false, false));

        Health health = indicator.health();

        assertThat(listenerDetail(health, "listener-0"))
            .containsEntry("stoppedTopic", "mopl.follow.events")
            .containsEntry("reason", "DLT 발행이 3회 연속 실패했습니다.")
            .containsEntry("stoppedAt", NOW.toString());
    }

    /**
     * 자동 기동을 꺼 둔 환경에서는 컨테이너가 멈춰 있는 것이 정상입니다. 실행 여부만 보면
     * 그런 환경이 전부 실패로 잡힙니다.
     */
    @Test
    @DisplayName("기동하지 않은 리스너는 실패로 보지 않는다")
    void health_up_whenContainerNeverStarted() {
        register(container("listener-0", false, true));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(listenerDetail(health, "listener-0"))
            .containsEntry("running", false)
            .containsEntry("stoppedAbnormally", false);
    }

    /**
     * 다시 띄운 뒤에도 중지 기록은 남습니다. 그것까지 보여주면 이미 해소된 원인을 현재
     * 상태처럼 읽게 됩니다.
     */
    @Test
    @DisplayName("복구된 리스너의 상세에는 지난 중지 사유를 붙이지 않는다")
    void health_up_afterRecovery_dropsStaleReason() {
        stopTracker.recordDeadLetterStop(
            GROUP, "listener-0", "mopl.follow.events", "DLT 발행이 3회 연속 실패했습니다.");
        register(container("listener-0", true, true));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(listenerDetail(health, "listener-0")).doesNotContainKey("reason");
    }

    @Test
    @DisplayName("리스너 하나만 멈춰도 전체를 DOWN으로 보고한다")
    void health_down_whenAnyContainerStopped() {
        register(
            container("listener-0", true, true),
            container("listener-1", false, false));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("containers", 2);
    }

    @Test
    @DisplayName("등록된 리스너가 없으면 UP을 보고한다")
    void health_up_whenNoContainers() {
        register();

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("containers", 0);
    }
}
