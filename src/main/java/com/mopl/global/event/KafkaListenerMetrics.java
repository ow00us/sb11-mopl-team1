package com.mopl.global.event;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

/**
 * Kafka 리스너 컨테이너의 실행 상태를 지표로 노출합니다.
 *
 * <p>health 는 지금 어떤지를 알려주고 지표는 언제부터 그런지를 알려줍니다. 소비가 멈춘 시점을
 * 되짚어야 밀린 lag 과 재처리 범위를 정할 수 있습니다.
 *
 * <p>수집 시점마다 컨테이너 목록을 읽습니다. 메모리에 있는 컬렉션을 세는 것이라 주기가 그대로
 * 부하가 되지 않습니다. Outbox 지표처럼 별도 갱신 주기를 두지 않는 이유입니다.
 *
 * <p>gauge 가 상태를 참조하는 대상은 {@code KafkaListenerEndpointRegistry} 자신입니다.
 * Micrometer 는 gauge 대상을 약한 참조로 들고 있어, 수명이 짧은 객체를 넘기면 수집 시점에
 * 값이 사라집니다.
 */
@Component
public class KafkaListenerMetrics {

    public KafkaListenerMetrics(
        MeterRegistry meterRegistry, KafkaListenerEndpointRegistry listenerEndpointRegistry
    ) {
        gauge(meterRegistry, listenerEndpointRegistry, "running",
            "실행 중인 Kafka 리스너 컨테이너 수", true);
        gauge(meterRegistry, listenerEndpointRegistry, "stopped",
            "멈춰 있는 Kafka 리스너 컨테이너 수", false);
    }

    private void gauge(
        MeterRegistry meterRegistry,
        KafkaListenerEndpointRegistry registry,
        String state,
        String description,
        boolean running
    ) {
        Gauge.builder("mopl.kafka.listener.containers", registry,
                source -> count(source, running))
            .tag("state", state)
            .description(description)
            .register(meterRegistry);
    }

    private static double count(KafkaListenerEndpointRegistry registry, boolean running) {
        return registry.getListenerContainers().stream()
            .filter(container -> container.isRunning() == running)
            .count();
    }
}
