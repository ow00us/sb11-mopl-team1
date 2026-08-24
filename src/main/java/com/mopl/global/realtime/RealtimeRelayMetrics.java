package com.mopl.global.realtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 실시간 중계의 발행과 수신 결과를 지표로 노출합니다.
 *
 * <p>중계는 부가 경로라 실패를 호출부로 던지지 않습니다. 발행이 실패해도 도메인 요청은 정상
 * 응답하고 커밋까지 끝나므로 API 지표에는 아무 흔적이 없습니다. 다른 인스턴스에 연결된
 * 사용자만 조용히 메시지를 받지 못합니다. 그 상황을 드러내는 신호가 여기밖에 없습니다.
 *
 * <p>구독 컨테이너를 참조하지 않습니다. 컨테이너는 구독자를 필요로 하고 구독자는 이 클래스를
 * 필요로 하므로 여기서 컨테이너를 받으면 순환이 됩니다. 구독 상태는
 * {@link RealtimeRelayStateMetrics} 가 따로 노출합니다.
 *
 * <p>지표 기록이 전달을 방해하지 않게 합니다. 관측을 위해 넣은 코드가 관측 대상을 망가뜨리면
 * 안 됩니다. 기록에서 난 예외는 삼키고 debug 로만 남깁니다.
 */
@Slf4j
@Component
public class RealtimeRelayMetrics {

    /** 아직 한 번도 없었다는 뜻입니다. 방금 일어난 0초와 구분되어야 합니다. */
    public static final long NEVER = -1L;

    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private final Counter publishSucceeded;
    private final Counter publishFailed;
    private final Counter delivered;
    private final Map<RealtimeRelayDiscardReason, Counter> discarded =
        new EnumMap<>(RealtimeRelayDiscardReason.class);

    /** 마지막으로 다른 인스턴스의 메시지를 handler 까지 넘긴 시각입니다. */
    private final AtomicLong lastReceivedAtEpochSecond = new AtomicLong(NEVER);

    // 생성자가 둘이라 어느 쪽으로 주입할지 명시해야 합니다.
    @Autowired
    public RealtimeRelayMetrics(MeterRegistry meterRegistry) {
        this(meterRegistry, Clock.systemUTC());
    }

    /** 시각을 테스트가 정할 수 있게 하는 생성자입니다. */
    RealtimeRelayMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;

        this.publishSucceeded = publishCounter(meterRegistry, "succeeded",
            "다른 인스턴스로 내보낸 실시간 메시지 수");
        this.publishFailed = publishCounter(meterRegistry, "failed",
            "내보내지 못하고 건너뛴 실시간 메시지 수");

        this.delivered = Counter.builder("mopl.realtime.relay.delivered.messages")
            .description("다른 인스턴스에서 받아 목적지 handler 로 넘긴 메시지 수")
            .register(meterRegistry);

        for (RealtimeRelayDiscardReason reason : RealtimeRelayDiscardReason.values()) {
            discarded.put(reason, Counter.builder("mopl.realtime.relay.discarded.messages")
                .tag("reason", reason.tag())
                .description("받았지만 전달하지 않고 버린 메시지 수")
                .register(meterRegistry));
        }

        Gauge.builder("mopl.realtime.relay.last.received.age", this,
                RealtimeRelayMetrics::lastReceivedAgeSeconds)
            .description("마지막으로 다른 인스턴스의 메시지를 전달한 뒤 지난 시간. 아직 없으면 -1")
            .baseUnit("seconds")
            .register(meterRegistry);
    }

    private Counter publishCounter(MeterRegistry registry, String outcome, String description) {
        return Counter.builder("mopl.realtime.relay.published.messages")
            .tag("outcome", outcome)
            .description(description)
            .register(registry);
    }

    /**
     * 마지막 전달 이후 지난 시간입니다. 아직 없으면 {@link #NEVER} 입니다.
     *
     * <p>구독이 붙어 있다는 것과 메시지가 실제로 흐른다는 것은 다릅니다. 구독은 정상인데 값이
     * 계속 커지면 발행 쪽이 멈췄거나 채널이 갈렸다는 뜻입니다.
     */
    public long lastReceivedAgeSeconds() {
        long epochSecond = lastReceivedAtEpochSecond.get();
        if (epochSecond == NEVER) {
            return NEVER;
        }
        return Math.max(0L,
            Duration.between(Instant.ofEpochSecond(epochSecond), clock.instant()).toSeconds());
    }

    public void recordPublishSucceeded() {
        safely(publishSucceeded::increment);
    }

    public void recordPublishFailed() {
        safely(publishFailed::increment);
    }

    /** handler 까지 넘긴 메시지입니다. handler 안에서 난 실패는 따로 셉니다. */
    public void recordDelivered() {
        safely(() -> {
            delivered.increment();
            lastReceivedAtEpochSecond.set(clock.instant().getEpochSecond());
        });
    }

    public void recordDiscarded(RealtimeRelayDiscardReason reason) {
        safely(() -> discarded.get(reason).increment());
    }

    /**
     * handler 하나가 전달에 실패한 건수입니다.
     *
     * <p>handler 이름을 태그로 둡니다. 종류가 코드에 있는 만큼으로 제한되고, 어느 목적지가
     * 막혔는지가 원인 범위를 좁히는 첫 정보입니다.
     */
    public void recordHandlerFailure(String handlerName) {
        safely(() -> Counter.builder("mopl.realtime.relay.handler.failures")
            .tag("handler", handlerName)
            .description("목적지 handler 가 전달에 실패한 수")
            .register(meterRegistry)
            .increment());
    }

    private void safely(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException e) {
            // 관측을 위해 넣은 코드가 관측 대상을 망가뜨리면 안 됩니다.
            log.debug("실시간 중계 지표를 기록하지 못했습니다.", e);
        }
    }
}
