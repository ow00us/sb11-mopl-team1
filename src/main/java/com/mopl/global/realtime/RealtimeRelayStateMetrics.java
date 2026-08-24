package com.mopl.global.realtime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 실시간 중계 구독 상태를 지표로 노출합니다.
 *
 * <p>health 는 지금 어떤지를 알려주고 지표는 언제부터 그런지를 알려줍니다. 구독이 언제
 * 끊겼는지를 되짚어야 그동안 놓친 전달 범위를 정할 수 있습니다.
 *
 * <p>{@link RealtimeRelayMetrics} 와 나눠 둡니다. 구독 컨테이너는 구독자를 필요로 하고
 * 구독자는 발행·수신 지표를 필요로 하므로, 한 클래스에 합치면 순환이 됩니다.
 *
 * <p>중계를 꺼 둔 환경에서는 컨테이너가 없으므로 이 지표도 등록하지 않습니다.
 *
 * <p>gauge 가 상태를 참조하는 대상은 컨테이너 자신입니다. Micrometer 는 gauge 대상을 약한
 * 참조로 들고 있어, 수명이 짧은 객체를 넘기면 수집 시점에 값이 사라집니다.
 */
@Component
@ConditionalOnProperty(name = "mopl.realtime.relay.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeRelayStateMetrics {

    public RealtimeRelayStateMetrics(
        MeterRegistry meterRegistry, RealtimeRelayListenerContainer container
    ) {
        Gauge.builder("mopl.realtime.relay.subscribed", container,
                source -> source.isSubscribed() ? 1 : 0)
            .description("실시간 중계 채널을 실제로 구독하고 있으면 1")
            .register(meterRegistry);
    }
}
