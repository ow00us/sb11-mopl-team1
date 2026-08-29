package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaListenerStopTrackerTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");
    private static final String TOPIC = "mopl.follow.events";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final KafkaListenerStopTracker tracker = new KafkaListenerStopTracker(
        meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void closeRegistry() {
        meterRegistry.close();
    }

    @Test
    @DisplayName("null 또는 기록 없는 Consumer Group 조회는 빈 결과를 반환한다")
    void returnsEmptyForMissingGroup() {
        assertThat(tracker.lastStop(null)).isEmpty();
        assertThat(tracker.lastStop("not-recorded")).isEmpty();
    }

    @Test
    @DisplayName("같은 그룹의 마지막 원인만 갱신하고 다른 그룹의 기록은 보존한다")
    void replacesLatestStopWithinGroupOnly() {
        tracker.recordDeadLetterStop("group-a", "listener-a-0", TOPIC, "first failure");
        tracker.recordDeadLetterStop("group-b", "listener-b-0", TOPIC, "other failure");
        tracker.recordDeadLetterStop("group-a", "listener-a-1", TOPIC, "latest failure");

        assertThat(tracker.lastStop("group-a")).hasValueSatisfying(stop -> {
            assertThat(stop.listenerId()).isEqualTo("listener-a-1");
            assertThat(stop.reason()).isEqualTo("latest failure");
            assertThat(stop.stoppedAt()).isEqualTo(NOW);
        });
        assertThat(tracker.lastStop("group-b")).hasValueSatisfying(stop ->
            assertThat(stop.reason()).isEqualTo("other failure"));
        assertThat(meterRegistry.get("mopl.kafka.listener.stops")
            .tag("topic", TOPIC).counter().count()).isEqualTo(3);
    }
}
