package com.mopl.global.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 발행·수신 결과가 서로 구분되어 기록되는지 검증합니다.
 *
 * <p>버렸다는 사실만으로는 운영 판단이 서지 않습니다. 자기 인스턴스 메시지와 중복은 설계대로
 * 동작하고 있다는 뜻이고, 형식 오류와 필수 값 누락은 계약이 깨졌다는 뜻입니다. 두 부류가 한
 * 카운터에 섞이면 어느 쪽인지 알 수 없습니다.
 */
class RealtimeRelayMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-22T03:00:00Z");

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(NOW);
    private final RealtimeRelayMetrics metrics = new RealtimeRelayMetrics(meterRegistry, clock);

    private double published(String outcome) {
        return meterRegistry.get("mopl.realtime.relay.published.messages")
            .tag("outcome", outcome).counter().count();
    }

    private double discarded(String reason) {
        return meterRegistry.get("mopl.realtime.relay.discarded.messages")
            .tag("reason", reason).counter().count();
    }

    @Test
    @DisplayName("발행 성공과 실패를 나눠 센다")
    void countsPublishOutcomesSeparately() {
        metrics.recordPublishSucceeded();
        metrics.recordPublishSucceeded();
        metrics.recordPublishFailed();

        assertThat(published("succeeded")).isEqualTo(2);
        assertThat(published("failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("폐기 이유를 서로 구분해 센다")
    void countsDiscardReasonsSeparately() {
        metrics.recordDiscarded(RealtimeRelayDiscardReason.MALFORMED);
        metrics.recordDiscarded(RealtimeRelayDiscardReason.INCOMPLETE);
        metrics.recordDiscarded(RealtimeRelayDiscardReason.SELF);
        metrics.recordDiscarded(RealtimeRelayDiscardReason.SELF);
        metrics.recordDiscarded(RealtimeRelayDiscardReason.DUPLICATE);

        assertThat(discarded("malformed")).isEqualTo(1);
        assertThat(discarded("incomplete")).isEqualTo(1);
        assertThat(discarded("self")).isEqualTo(2);
        assertThat(discarded("duplicate")).isEqualTo(1);
    }

    @Test
    @DisplayName("handler 실패를 목적지별로 센다")
    void countsHandlerFailuresPerHandler() {
        metrics.recordHandlerFailure("NotificationHandler");
        metrics.recordHandlerFailure("NotificationHandler");
        metrics.recordHandlerFailure("DirectMessageHandler");

        assertThat(meterRegistry.get("mopl.realtime.relay.handler.failures")
            .tag("handler", "NotificationHandler").counter().count()).isEqualTo(2);
        assertThat(meterRegistry.get("mopl.realtime.relay.handler.failures")
            .tag("handler", "DirectMessageHandler").counter().count()).isEqualTo(1);
    }

    /**
     * 구독이 붙어 있다는 것과 메시지가 실제로 흐른다는 것은 다릅니다. 아직 한 번도 받지 않은
     * 상태를 방금 받은 0초와 같은 값으로 두면 그 둘을 구분할 수 없습니다.
     */
    @Test
    @DisplayName("아직 받은 적이 없으면 마지막 수신 경과를 -1로 둔다")
    void lastReceivedAge_isNeverBeforeFirstDelivery() {
        assertThat(metrics.lastReceivedAgeSeconds()).isEqualTo(RealtimeRelayMetrics.NEVER);
    }

    @Test
    @DisplayName("전달한 뒤 지난 시간을 마지막 수신 경과로 보고한다")
    void lastReceivedAge_growsAfterDelivery() {
        metrics.recordDelivered();
        assertThat(metrics.lastReceivedAgeSeconds()).isZero();

        clock.set(NOW.plusSeconds(90));

        assertThat(metrics.lastReceivedAgeSeconds()).isEqualTo(90);
        assertThat(meterRegistry.get("mopl.realtime.relay.last.received.age").gauge().value())
            .isEqualTo(90);
    }

    @Test
    @DisplayName("전달 건수를 센다")
    void countsDelivered() {
        metrics.recordDelivered();
        metrics.recordDelivered();

        assertThat(meterRegistry.get("mopl.realtime.relay.delivered.messages")
            .counter().count()).isEqualTo(2);
    }

    /**
     * 관측을 위해 넣은 코드가 관측 대상을 망가뜨리면 안 됩니다. 지표 기록이 실패했다고 실시간
     * 전달이나 그것을 호출한 도메인 트랜잭션이 함께 끊기면 손해가 더 큽니다.
     */
    @Test
    @DisplayName("지표 기록이 실패해도 호출부로 예외를 던지지 않는다")
    void recordingFailure_doesNotPropagate() {
        RealtimeRelayMetrics failing = new RealtimeRelayMetrics(new ThrowingMeterRegistry(), clock);

        assertThatCode(() -> {
            failing.recordPublishSucceeded();
            failing.recordPublishFailed();
            failing.recordDelivered();
            failing.recordDiscarded(RealtimeRelayDiscardReason.SELF);
            failing.recordHandlerFailure("NotificationHandler");
        }).doesNotThrowAnyException();
    }

    /** 등록은 되지만 값을 올릴 때 실패하는 registry 입니다. */
    private static class ThrowingMeterRegistry extends SimpleMeterRegistry {

        @Override
        protected Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
            return new Counter() {
                @Override
                public void increment(double amount) {
                    throw new IllegalStateException("지표 저장소 오류");
                }

                @Override
                public double count() {
                    return 0;
                }

                @Override
                public Id getId() {
                    return id;
                }
            };
        }
    }

    /** 경과 시간을 확인하려면 시각을 옮길 수 있어야 합니다. */
    private static class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
