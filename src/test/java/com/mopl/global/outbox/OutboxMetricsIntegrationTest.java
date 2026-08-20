package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Outbox 지표가 실제 적재 상태를 따라가는지 검증합니다.
 *
 * <p>gauge 는 집계 질의 결과를 담으므로 실제 데이터베이스가 필요합니다. 값을 직접 넣어
 * 확인하면 질의 조건이 틀려도 테스트가 통과합니다.
 *
 * <p>갱신 시점은 테스트가 정합니다. test 프로파일에서 주기 갱신을 꺼 두었습니다.
 *
 * <p>{@code @AutoConfigureObservability} 가 필요합니다. Spring Boot 테스트는 기본적으로
 * 지표 export 를 꺼서 Prometheus registry 가 만들어지지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureObservability(tracing = false)
class OutboxMetricsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final Instant NOW = Instant.parse("2026-08-15T03:00:00Z");

    @Autowired
    OutboxMetrics outboxMetrics;

    @Autowired
    OutboxStatusWriter outboxStatusWriter;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    PrometheusMeterRegistry prometheusMeterRegistry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        outboxEventRepository.deleteAll();
        outboxMetrics.refresh(NOW);
    }

    private OutboxEvent save(Instant occurredAt) {
        UUID aggregateId = UUID.randomUUID();
        return outboxEventRepository.saveAndFlush(new OutboxEvent(
            UUID.randomUUID(), "follow.created", 1, aggregateId, occurredAt,
            "{\"followerId\":\"a\"}", aggregateId.toString(), "AGGREGATE",
            "follow.created:" + aggregateId, occurredAt));
    }

    private double gauge(String state) {
        return meterRegistry.get("mopl.outbox.events").tag("state", state).gauge().value();
    }

    private double oldestPendingAge() {
        return meterRegistry.get("mopl.outbox.oldest.pending.age").gauge().value();
    }

    private double relayRecords(String outcome) {
        return meterRegistry.get("mopl.outbox.relay.records").tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("발행 대기·선점·최종 실패 건수를 상태별로 노출한다")
    void gauges_followBacklogState() {
        save(NOW.minusSeconds(10));
        save(NOW.minusSeconds(20));
        OutboxEvent claimed = save(NOW.minusSeconds(30));
        OutboxEvent failed = save(NOW.minusSeconds(40));

        jdbcTemplate.update(
            "UPDATE outbox_events SET claim_owner = 'relay-1' WHERE id = ?", claimed.getId());
        jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'FAILED' WHERE id = ?", failed.getId());

        outboxMetrics.refresh(NOW);

        // 선점 중인 레코드도 아직 발행 전이므로 대기에 포함됩니다.
        assertThat(gauge("pending")).isEqualTo(3.0);
        assertThat(gauge("claimed")).isEqualTo(1.0);
        assertThat(gauge("failed")).isEqualTo(1.0);
    }

    /**
     * 발행이 막혀 있는지는 건수만으로 알기 어렵습니다.
     *
     * <p>대기 건수는 유입이 많아도 늘어납니다. 가장 오래된 대기 이벤트가 얼마나 오래 남아
     * 있는지가 전달이 멈췄는지를 가르는 신호입니다.
     */
    @Test
    @DisplayName("가장 오래된 발행 대기 이벤트의 경과 시간을 노출한다")
    void oldestPendingAge_tracksBacklogLatency() {
        save(NOW.minus(5, ChronoUnit.MINUTES));
        save(NOW.minus(1, ChronoUnit.MINUTES));

        outboxMetrics.refresh(NOW);

        assertThat(oldestPendingAge()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("발행 대기 이벤트가 없으면 경과 시간은 0이다")
    void oldestPendingAge_isZeroWhenBacklogEmpty() {
        outboxMetrics.refresh(NOW);

        assertThat(oldestPendingAge()).isZero();
    }

    @Test
    @DisplayName("발행 완료·재시도·최종 실패 건수를 결과별로 센다")
    void counters_recordRelayOutcomes() {
        OutboxEvent published = save(NOW);
        OutboxEvent retried = save(NOW);

        double publishedBefore = relayRecords("published");
        double retriedBefore = relayRecords("retried");
        double exhaustedBefore = relayRecords("exhausted");

        outboxStatusWriter.markPublished(published.getId(), NOW);
        outboxStatusWriter.markAttemptFailed(retried.getId(), "일시적인 broker 오류", NOW);

        assertThat(relayRecords("published")).isEqualTo(publishedBefore + 1);
        assertThat(relayRecords("retried")).isEqualTo(retriedBefore + 1);
        assertThat(relayRecords("exhausted")).isEqualTo(exhaustedBefore);

        // application-test.yml 의 max-attempts 가 3 입니다.
        outboxStatusWriter.markAttemptFailed(retried.getId(), "일시적인 broker 오류", NOW);
        outboxStatusWriter.markAttemptFailed(retried.getId(), "일시적인 broker 오류", NOW);

        assertThat(relayRecords("exhausted")).isEqualTo(exhaustedBefore + 1);
    }

    /**
     * Prometheus 노출 형식에 이름이 실제로 나오는지 확인합니다.
     *
     * <p>registry 에 등록됐다고 수집되는 것은 아닙니다. 이름에 쓸 수 없는 문자가 있거나
     * 형식이 맞지 않으면 이 단계에서 사라집니다.
     */
    @Test
    @DisplayName("Prometheus 형식에 Outbox 지표 이름이 나온다")
    void metricsAppearInPrometheusScrape() {
        save(NOW);
        outboxMetrics.refresh(NOW);

        String scrape = prometheusMeterRegistry.scrape();

        assertThat(scrape)
            .contains("mopl_outbox_events")
            .contains("mopl_outbox_oldest_pending_age_seconds")
            .contains("mopl_outbox_relay_batch_size")
            .contains("mopl_outbox_relay_duration_seconds");
    }
}
