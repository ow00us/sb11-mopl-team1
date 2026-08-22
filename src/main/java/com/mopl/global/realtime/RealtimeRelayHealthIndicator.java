package com.mopl.global.realtime;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 실시간 중계 구독 상태를 health 로 노출합니다.
 *
 * <p>구독이 붙지 않아도 애플리케이션은 기동합니다. 중계는 부가 경로이므로 Redis 가 준비되지
 * 않았다고 REST 와 도메인 기능까지 세울 이유가 없습니다. 그 선택의 대가가 조용한 장애입니다.
 * 도메인 요청은 정상 응답하고, 다른 인스턴스에 연결된 사용자만 메시지를 받지 못합니다.
 *
 * <p>이 component 는 liveness 와 readiness 어느 group 에도 넣지 않습니다. Kafka 리스너 중지와
 * 같은 이유입니다. liveness 에 넣으면 Redis 가 복구되지 않은 채 재시작만 반복되고, readiness 에
 * 넣으면 REST 를 처리할 수 있는 인스턴스가 로드밸런서에서 빠집니다. 대신 전체
 * {@code /actuator/health} 집계에는 반영되어 조용히 {@code UP} 으로 남지 않습니다.
 *
 * <p>중계를 꺼 둔 환경에서는 등록하지 않습니다. 구독하지 않는 것이 설정대로 동작하는 상태인데
 * 그것을 실패로 보고하면 판정이 뒤집힙니다.
 */
@Component("realtimeRelay")
@ConditionalOnProperty(name = "mopl.realtime.relay.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeRelayHealthIndicator implements HealthIndicator {

    private final RealtimeRelayListenerContainer container;
    private final RealtimeRelayMetrics metrics;
    private final RealtimeInstanceId instanceId;
    private final Duration retryInterval;

    public RealtimeRelayHealthIndicator(
        RealtimeRelayListenerContainer container,
        RealtimeRelayMetrics metrics,
        RealtimeInstanceId instanceId,
        @Value("${mopl.realtime.relay.subscribe-retry-interval}") Duration retryInterval
    ) {
        this.container = container;
        this.metrics = metrics;
        this.instanceId = instanceId;
        this.retryInterval = retryInterval;
    }

    @Override
    public Health health() {
        boolean subscribed = container.isSubscribed();

        Health.Builder builder = subscribed ? Health.up() : Health.down();
        builder
            .withDetail("subscribed", subscribed)
            .withDetail("channel", RealtimeChannels.MESSAGES)
            .withDetail("instanceId", instanceId.value());

        // 구독이 붙지 않은 상태는 방치되지 않습니다. 운영자가 그 사실을 알아야 기다릴지
        // 개입할지 판단할 수 있으므로 재시도 주기를 함께 보여줍니다.
        if (!subscribed) {
            builder.withDetail("retrying", true);
            builder.withDetail("retryInterval", retryInterval.toString());
        }

        long lastReceivedAge = metrics.lastReceivedAgeSeconds();
        if (lastReceivedAge != RealtimeRelayMetrics.NEVER) {
            builder.withDetail("lastReceivedAgeSeconds", lastReceivedAge);
        }
        return builder.build();
    }
}
