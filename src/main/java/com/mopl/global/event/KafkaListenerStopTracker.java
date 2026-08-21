package com.mopl.global.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 리스너 컨테이너가 왜 멈췄는지를 기록합니다.
 *
 * <p>컨테이너의 실행 여부는 {@code KafkaListenerEndpointRegistry} 가 알고 있습니다. 하지만
 * 거기에는 원인이 없습니다. 중지 사유는 중지시킨 쪽만 알고 있고 지금은 로그에만 남아, 운영자가
 * 로그를 뒤지기 전까지 무엇 때문에 소비가 멈췄는지 알 수 없습니다.
 *
 * <p>Consumer Group 을 키로 씁니다. 동시성 설정이 있으면 실제로 멈추는 것은 자식 컨테이너라
 * {@code listenerId} 가 부모와 다르지만, Consumer Group 은 같아서 health 가 부모 컨테이너를
 * 훑을 때 그대로 맞춰집니다.
 *
 * <p>기록은 덮어씁니다. 마지막 중지 원인이 지금 운영자가 풀어야 할 문제이고, 이전 이력은
 * 로그와 지표에 남습니다.
 */
@Slf4j
@Component
public class KafkaListenerStopTracker {

    private final Map<String, KafkaListenerStop> lastStops = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    // 생성자가 둘이라 어느 쪽으로 주입할지 명시해야 합니다.
    @Autowired
    public KafkaListenerStopTracker(MeterRegistry meterRegistry) {
        this(meterRegistry, Clock.systemUTC());
    }

    /** 시각을 테스트가 정할 수 있게 하는 생성자입니다. */
    KafkaListenerStopTracker(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * DLT 발행 실패로 컨테이너를 멈춘 사실을 남깁니다.
     *
     * <p>중지 횟수를 토픽별로 셉니다. 어떤 토픽의 소비가 반복해서 멈추는지가 원인 범위를
     * 좁히는 첫 정보입니다.
     */
    public void recordDeadLetterStop(
        String groupId, String listenerId, String topic, String reason
    ) {
        KafkaListenerStop stop = new KafkaListenerStop(
            groupId, listenerId, topic, reason, clock.instant());
        lastStops.put(groupId, stop);

        Counter.builder("mopl.kafka.listener.stops")
            .tag("topic", topic)
            .description("DLT 발행 실패로 중지한 리스너 컨테이너 수")
            .register(meterRegistry)
            .increment();

        log.error("리스너 중지를 기록했습니다. groupId={}, listenerId={}, topic={}, reason={}",
            groupId, listenerId, topic, reason);
    }

    /**
     * 해당 Consumer Group 의 마지막 중지 기록입니다.
     *
     * <p>다시 띄운 뒤에도 기록은 남습니다. 지우지 않는 대신 health 가 지금 비정상 중지 상태인
     * 컨테이너에만 이 값을 붙입니다. 판정은 컨테이너의 실제 상태에서 읽고, 이 기록은 그 판정에
     * 설명을 더하는 용도입니다. 중지 시각을 함께 담는 이유도 그 설명이 언제 것인지 드러내기
     * 위해서입니다.
     */
    public Optional<KafkaListenerStop> lastStop(String groupId) {
        return groupId == null ? Optional.empty() : Optional.ofNullable(lastStops.get(groupId));
    }
}
