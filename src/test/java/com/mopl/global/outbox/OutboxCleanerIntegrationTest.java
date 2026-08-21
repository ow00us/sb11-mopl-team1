package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 발행 완료 레코드 정리를 실제 데이터베이스로 검증합니다.
 *
 * <p>삭제 조건이 상태와 시각에 걸려 있어 모킹하면 "삭제 메서드를 호출했다"만 확인됩니다.
 * 무엇이 남고 무엇이 사라지는지가 이 기능의 전부입니다.
 *
 * <p>주기 실행은 test 프로파일에서 꺼 두고 호출 시점을 테스트가 정합니다. 시각도 테스트가
 * 정해 보관 기간이 지나기를 실제로 기다리지 않습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(OutboxCleanerIntegrationTest.CleanerConfig.class)
class OutboxCleanerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final Instant NOW = Instant.parse("2026-08-20T03:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    OutboxMetrics outboxMetrics;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    MutableClock cleanupClock;

    @Autowired
    @Qualifier("testOutboxCleaner")
    OutboxCleaner outboxCleaner;

    @Autowired
    @Qualifier("limitedOutboxCleaner")
    OutboxCleaner limitedCleaner;

    /**
     * 정리기를 빈으로 등록합니다.
     *
     * <p>직접 만들면 프록시를 타지 않아 {@code @Transactional} 이 적용되지 않고, 삭제 질의가
     * 트랜잭션 없이 실행되어 거부됩니다.
     */
    @TestConfiguration
    static class CleanerConfig {

        @Bean
        MutableClock cleanupClock() {
            return new MutableClock(NOW);
        }

        @Bean
        OutboxCleaner testOutboxCleaner(
            OutboxEventRepository outboxEventRepository,
            OutboxMetrics outboxMetrics,
            MutableClock cleanupClock
        ) {
            return new OutboxCleaner(
                outboxEventRepository, outboxMetrics, RETENTION, 1000, cleanupClock);
        }

        /** 삭제 상한 동작을 보기 위한 정리기입니다. */
        @Bean
        OutboxCleaner limitedOutboxCleaner(
            OutboxEventRepository outboxEventRepository,
            OutboxMetrics outboxMetrics,
            MutableClock cleanupClock
        ) {
            return new OutboxCleaner(
                outboxEventRepository, outboxMetrics, RETENTION, 2, cleanupClock);
        }
    }

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        cleanupClock.set(NOW);
    }

    private OutboxEvent save(Instant occurredAt) {
        UUID aggregateId = UUID.randomUUID();
        return outboxEventRepository.saveAndFlush(new OutboxEvent(
            UUID.randomUUID(), "follow.created", 1, aggregateId, occurredAt,
            "{\"followerId\":\"a\"}", aggregateId.toString(), "NONE",
            "follow.created:" + UUID.randomUUID(), occurredAt));
    }

    /** 발행 완료는 relay 가 만드는 상태입니다. 여기서는 relay 없이 상태만 맞춥니다. */
    private OutboxEvent savePublished(Instant publishedAt) {
        OutboxEvent saved = save(publishedAt);
        jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
            Timestamp.from(publishedAt), saved.getId());
        return saved;
    }

    private OutboxEvent saveFailed() {
        OutboxEvent saved = save(NOW.minus(Duration.ofDays(30)));
        jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'FAILED' WHERE id = ?", saved.getId());
        return saved;
    }

    private boolean exists(OutboxEvent event) {
        return outboxEventRepository.findById(event.getId()).isPresent();
    }

    private double cleanedCount() {
        return meterRegistry.get("mopl.outbox.cleaned.records").counter().count();
    }

    @Test
    @DisplayName("보관 기간을 지난 발행 완료 레코드를 지운다")
    void clean_deletesPublishedBeyondRetention() {
        OutboxEvent old = savePublished(NOW.minus(Duration.ofDays(8)));

        assertThat(outboxCleaner.clean()).isEqualTo(1);
        assertThat(exists(old)).isFalse();
    }

    /**
     * 경계는 포함입니다. 보관 기간이 정확히 지난 시점의 레코드는 지웁니다.
     */
    @Test
    @DisplayName("보관 기간 경계에 걸린 레코드는 지우고 그 직전은 남긴다")
    void clean_respectsRetentionBoundary() {
        OutboxEvent onBoundary = savePublished(NOW.minus(RETENTION));
        OutboxEvent justInside = savePublished(NOW.minus(RETENTION).plusSeconds(1));

        assertThat(outboxCleaner.clean()).isEqualTo(1);
        assertThat(exists(onBoundary)).isFalse();
        assertThat(exists(justInside)).isTrue();
    }

    /**
     * 아직 나가지 않았거나 사람이 처리해야 할 이벤트입니다. 보관 기간과 무관하게 남아야 합니다.
     */
    @Test
    @DisplayName("발행 대기와 최종 실패 레코드는 보관 기간과 무관하게 남는다")
    void clean_keepsPendingAndFailed() {
        OutboxEvent pending = save(NOW.minus(Duration.ofDays(30)));
        OutboxEvent failed = saveFailed();

        assertThat(outboxCleaner.clean()).isZero();
        assertThat(exists(pending)).isTrue();
        assertThat(exists(failed)).isTrue();
    }

    @Test
    @DisplayName("한 번의 실행이 삭제 상한을 넘기지 않는다")
    void clean_stopsAtBatchSize() {
        for (int i = 0; i < 5; i++) {
            savePublished(NOW.minus(Duration.ofDays(8 + i)));
        }

        assertThat(limitedCleaner.clean()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(3);
    }

    /**
     * 남은 것은 다음 실행으로 넘어갑니다. 상한 때문에 영영 남는 레코드가 있으면 안 됩니다.
     */
    @Test
    @DisplayName("상한에 걸려 남은 레코드는 다음 실행에서 지운다")
    void clean_continuesOnNextRun() {
        for (int i = 0; i < 3; i++) {
            savePublished(NOW.minus(Duration.ofDays(8 + i)));
        }

        assertThat(limitedCleaner.clean()).isEqualTo(2);
        assertThat(limitedCleaner.clean()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isZero();
    }

    /**
     * 발행 완료 상태라면 발행 시각이 있어야 하지만, 비교가 성립하지 않는 값을 지우는 쪽으로
     * 두지 않습니다.
     */
    @Test
    @DisplayName("발행 시각이 없는 레코드는 지우지 않는다")
    void clean_keepsRecordWithoutPublishedAt() {
        OutboxEvent saved = save(NOW.minus(Duration.ofDays(30)));
        jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'PUBLISHED' WHERE id = ?", saved.getId());

        assertThat(outboxCleaner.clean()).isZero();
        assertThat(exists(saved)).isTrue();
    }

    @Test
    @DisplayName("지운 건수를 지표로 센다")
    void clean_recordsDeletedCount() {
        savePublished(NOW.minus(Duration.ofDays(8)));
        savePublished(NOW.minus(Duration.ofDays(9)));
        double before = cleanedCount();

        outboxCleaner.clean();

        assertThat(cleanedCount()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("지울 대상이 없으면 아무것도 지우지 않는다")
    void clean_returnsZeroWhenNothingExpired() {
        savePublished(NOW.minus(Duration.ofDays(1)));

        assertThat(outboxCleaner.clean()).isZero();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제 상한이 1보다 작으면 거부한다")
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new OutboxCleaner(
            outboxEventRepository, outboxMetrics, RETENTION, 0, cleanupClock))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
