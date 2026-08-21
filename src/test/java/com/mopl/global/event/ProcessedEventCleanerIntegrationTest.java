package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 멱등 처리 기록 정리를 실제 데이터베이스로 검증합니다.
 *
 * <p>삭제 조건이 시각에 걸려 있고 동시 실행 동작이 잠금에 달려 있어 모킹으로는 확인되지
 * 않습니다.
 *
 * <p>주기 실행은 test 프로파일에서 꺼 두고 호출 시점을 테스트가 정합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(ProcessedEventCleanerIntegrationTest.CleanerConfig.class)
class ProcessedEventCleanerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final Instant NOW = Instant.parse("2026-08-20T03:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(30);

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("testProcessedEventCleaner")
    ProcessedEventCleaner cleaner;

    @Autowired
    @Qualifier("limitedProcessedEventCleaner")
    ProcessedEventCleaner limitedCleaner;

    /**
     * 정리기를 빈으로 등록합니다.
     *
     * <p>직접 만들면 프록시를 타지 않아 {@code @Transactional} 이 적용되지 않고, 삭제 질의가
     * 트랜잭션 없이 실행되어 거부됩니다.
     */
    @TestConfiguration
    static class CleanerConfig {

        private static final Clock FIXED = Clock.fixed(NOW, ZoneId.of("UTC"));

        @Bean
        ProcessedEventCleaner testProcessedEventCleaner(
            ProcessedEventRepository repository, MeterRegistry meterRegistry
        ) {
            return new ProcessedEventCleaner(repository, meterRegistry, RETENTION, 1000, FIXED);
        }

        /** 삭제 상한 동작을 보기 위한 정리기입니다. */
        @Bean
        ProcessedEventCleaner limitedProcessedEventCleaner(
            ProcessedEventRepository repository, MeterRegistry meterRegistry
        ) {
            return new ProcessedEventCleaner(repository, meterRegistry, RETENTION, 2, FIXED);
        }
    }

    @BeforeEach
    void clear() {
        processedEventRepository.deleteAll();
    }

    /**
     * 기록 시각을 직접 정합니다.
     *
     * <p>{@code createdAt} 은 JPA Auditing 이 채우므로 저장 시점 값이 들어갑니다. 보관 기간
     * 경계를 확인하려면 그 값을 옮겨야 합니다.
     */
    private UUID saveRecordedAt(Instant createdAt) {
        ProcessedEvent saved = processedEventRepository.saveAndFlush(
            new ProcessedEvent("mopl.test", UUID.randomUUID(), "follow.created"));
        jdbcTemplate.update(
            "UPDATE processed_events SET created_at = ? WHERE id = ?",
            Timestamp.from(createdAt), saved.getId());
        return saved.getId();
    }

    private boolean exists(UUID id) {
        return processedEventRepository.findById(id).isPresent();
    }

    private double cleanedCount() {
        return meterRegistry.get("mopl.kafka.processed.cleaned.records").counter().count();
    }

    @Test
    @DisplayName("보관 기간을 지난 기록을 지운다")
    void clean_deletesRecordsBeyondRetention() {
        UUID old = saveRecordedAt(NOW.minus(Duration.ofDays(31)));

        assertThat(cleaner.clean()).isEqualTo(1);
        assertThat(exists(old)).isFalse();
    }

    /**
     * 보관 기간을 짧게 잡으면 지운 기록의 이벤트가 다시 왔을 때 처음 보는 것으로 판정되어
     * 도메인 부수 효과가 한 번 더 일어납니다. 경계는 정확해야 합니다.
     */
    @Test
    @DisplayName("보관 기간 경계에 걸린 기록은 지우고 그 직전은 남긴다")
    void clean_respectsRetentionBoundary() {
        UUID onBoundary = saveRecordedAt(NOW.minus(RETENTION));
        UUID justInside = saveRecordedAt(NOW.minus(RETENTION).plusSeconds(1));

        assertThat(cleaner.clean()).isEqualTo(1);
        assertThat(exists(onBoundary)).isFalse();
        assertThat(exists(justInside)).isTrue();
    }

    @Test
    @DisplayName("한 번의 실행이 삭제 상한을 넘기지 않는다")
    void clean_stopsAtBatchSize() {
        for (int i = 0; i < 5; i++) {
            saveRecordedAt(NOW.minus(Duration.ofDays(31 + i)));
        }

        assertThat(limitedCleaner.clean()).isEqualTo(2);
        assertThat(processedEventRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("상한에 걸려 남은 기록은 다음 실행에서 지운다")
    void clean_continuesOnNextRun() {
        for (int i = 0; i < 3; i++) {
            saveRecordedAt(NOW.minus(Duration.ofDays(31 + i)));
        }

        assertThat(limitedCleaner.clean()).isEqualTo(2);
        assertThat(limitedCleaner.clean()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("지울 대상이 없으면 아무것도 지우지 않는다")
    void clean_returnsZeroWhenNothingExpired() {
        saveRecordedAt(NOW.minus(Duration.ofDays(1)));

        assertThat(cleaner.clean()).isZero();
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("지운 건수를 지표로 센다")
    void clean_recordsDeletedCount() {
        saveRecordedAt(NOW.minus(Duration.ofDays(31)));
        saveRecordedAt(NOW.minus(Duration.ofDays(32)));
        double before = cleanedCount();

        cleaner.clean();

        assertThat(cleanedCount()).isEqualTo(before + 2);
    }

    /**
     * 두 인스턴스가 동시에 정리를 도는 상황입니다.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 로 잠그므로 같은 행을 두 번 지우려 하거나 서로 잠금을
     * 기다리지 않아야 합니다. 둘이 지운 건수의 합이 전체와 같고, 남는 행이 없어야 합니다.
     */
    @Test
    @DisplayName("두 인스턴스가 동시에 정리해도 중복이나 잠금 대기 없이 끝난다")
    void concurrentClean_doesNotOverlap() throws Exception {
        for (int i = 0; i < 10; i++) {
            saveRecordedAt(NOW.minus(Duration.ofDays(31 + i)));
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = List.of(
                executor.submit(() -> {
                    start.await();
                    return cleaner.clean();
                }),
                executor.submit(() -> {
                    start.await();
                    return cleaner.clean();
                }));

            start.countDown();

            int total = 0;
            for (Future<Integer> result : results) {
                total += result.get(10, TimeUnit.SECONDS);
            }

            assertThat(total).isEqualTo(10);
            assertThat(processedEventRepository.count()).isZero();
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 정리가 필요한 상황이란 곧 이 테이블이 크다는 뜻입니다. 인덱스 없이 두면 정리를 시작하는
     * 시점이 가장 비싼 시점이 됩니다.
     *
     * <p>테스트 데이터가 몇 건뿐이라 계획이 전체 훑기나 bitmap 조회로 기웁니다. 그 둘을 막아
     * 인덱스를 차례대로 읽는 계획이 성립하는지, 그때 정렬 단계가 사라지는지를 봅니다. 인덱스가
     * {@code (created_at, id)} 순서까지 담고 있어야 그렇게 됩니다.
     */
    @Test
    @DisplayName("정리 대상 조회가 created_at 인덱스를 사용한다")
    void cleanupQuery_usesCreatedAtIndex() {
        saveRecordedAt(NOW.minus(Duration.ofDays(31)));

        // 설정과 조회가 같은 연결에서 일어나야 하므로 연결을 직접 잡습니다.
        String rendered = jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                statement.execute("SET enable_bitmapscan = off");
                try (ResultSet rows = statement.executeQuery("""
                    EXPLAIN
                    SELECT id FROM processed_events
                    WHERE created_at <= '2026-07-21T03:00:00Z'
                    ORDER BY created_at, id
                    LIMIT 1000
                    FOR UPDATE SKIP LOCKED
                    """)) {
                    StringBuilder plan = new StringBuilder();
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append(System.lineSeparator());
                    }
                    return plan.toString();
                } finally {
                    statement.execute("RESET enable_seqscan");
                    statement.execute("RESET enable_bitmapscan");
                }
            }
        });

        assertThat(rendered).contains("Index Scan using idx_processed_events_created_at");
        assertThat(rendered).doesNotContain("Sort");
    }

    @Test
    @DisplayName("삭제 상한이 1보다 작으면 거부한다")
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> new ProcessedEventCleaner(
            processedEventRepository, meterRegistry, RETENTION, 0,
            Clock.fixed(NOW, ZoneId.of("UTC"))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
