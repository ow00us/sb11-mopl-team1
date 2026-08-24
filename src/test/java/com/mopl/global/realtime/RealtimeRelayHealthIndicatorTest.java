package com.mopl.global.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * 구독이 붙지 않은 상태가 health 로 드러나는지 검증합니다.
 *
 * <p>구독 실패는 기동을 막지 않습니다. 그 선택의 대가가 조용한 장애입니다. 도메인 요청은 정상
 * 응답하고 다른 인스턴스에 연결된 사용자만 메시지를 받지 못합니다.
 */
class RealtimeRelayHealthIndicatorTest {

    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);

    private final RealtimeRelayListenerContainer container =
        mock(RealtimeRelayListenerContainer.class);
    private final RealtimeRelayMetrics metrics =
        new RealtimeRelayMetrics(new SimpleMeterRegistry());

    private Health health() {
        return new RealtimeRelayHealthIndicator(
            container, metrics, new RealtimeInstanceId("instance-1"), RETRY_INTERVAL).health();
    }

    @Test
    @DisplayName("구독이 붙어 있으면 UP을 보고한다")
    void health_up_whenSubscribed() {
        when(container.isSubscribed()).thenReturn(true);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("subscribed", true)
            .containsEntry("channel", RealtimeChannels.MESSAGES)
            .containsEntry("instanceId", "instance-1");
    }

    @Test
    @DisplayName("구독이 붙지 않았으면 DOWN을 보고한다")
    void health_down_whenNotSubscribed() {
        when(container.isSubscribed()).thenReturn(false);

        Health health = health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("subscribed", false);
    }

    /**
     * 붙지 않은 상태가 방치되는지 다시 시도되는 중인지에 따라 운영자가 할 일이 다릅니다.
     */
    @Test
    @DisplayName("구독이 붙지 않았으면 재시도 중임을 상세에 남긴다")
    void health_down_showsRetryingState() {
        when(container.isSubscribed()).thenReturn(false);

        Health health = health();

        assertThat(health.getDetails())
            .containsEntry("retrying", true)
            .containsEntry("retryInterval", "PT30S");
    }

    @Test
    @DisplayName("구독이 정상이면 재시도 상태를 붙이지 않는다")
    void health_up_omitsRetryingState() {
        when(container.isSubscribed()).thenReturn(true);

        assertThat(health().getDetails()).doesNotContainKey("retrying");
    }

    /**
     * 구독은 정상인데 메시지가 흐르지 않는 상황을 구분해야 합니다. 아직 한 번도 받지 않은
     * 상태를 0초로 보여주면 방금 받은 것처럼 읽힙니다.
     */
    @Test
    @DisplayName("아직 받은 적이 없으면 마지막 수신 경과를 상세에 넣지 않는다")
    void health_omitsLastReceivedAge_beforeFirstDelivery() {
        when(container.isSubscribed()).thenReturn(true);

        assertThat(health().getDetails()).doesNotContainKey("lastReceivedAgeSeconds");
    }

    @Test
    @DisplayName("받은 적이 있으면 마지막 수신 경과를 상세에 남긴다")
    void health_showsLastReceivedAge() {
        when(container.isSubscribed()).thenReturn(true);
        metrics.recordDelivered();

        assertThat(health().getDetails()).containsKey("lastReceivedAgeSeconds");
    }
}
