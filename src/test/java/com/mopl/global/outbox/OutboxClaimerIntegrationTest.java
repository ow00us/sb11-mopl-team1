package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 선점과 lease 회수를 실제 데이터베이스로 검증합니다.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} 동작은 실제 PostgreSQL 에서만 확인됩니다. 모킹하면
 * 두 인스턴스가 같은 행을 가져가지 않는다는 성질이 검증되지 않습니다.
 *
 * <p>판정 시각을 파라미터로 넘겨 lease 만료를 기다리지 않고 검증합니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OutboxClaimerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final Instant BASE = Instant.parse("2026-08-14T03:00:00Z");
    private static final String OWNER = "relay-1";
    private static final String OTHER_OWNER = "relay-2";

    @Autowired
    OutboxClaimer outboxClaimer;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        outboxEventRepository.deleteAll();
    }

    private OutboxEvent pending(String partitionKey, Instant nextAttemptAt) {
        return new OutboxEvent(
            UUID.randomUUID(), "follow.created", 1, UUID.randomUUID(), nextAttemptAt,
            "{\"followerId\":\"a\"}", partitionKey, "NONE",
            "follow.created:" + partitionKey, nextAttemptAt);
    }

    private List<OutboxEvent> savePending(int count, Instant nextAttemptAt) {
        List<OutboxEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(pending("key-" + i, nextAttemptAt.plus(i, ChronoUnit.SECONDS)));
        }
        return outboxEventRepository.saveAllAndFlush(events);
    }

    @Test
    @DisplayName("batch 크기만큼만 선점하고 다음 시도 시각이 이른 순으로 돌려준다")
    void claim_respectsBatchSizeAndOrder() {
        savePending(5, BASE);

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 3, BASE.plusSeconds(60));

        assertThat(claimed).hasSize(3);
        assertThat(claimed).extracting(OutboxEvent::getPartitionKey)
            .containsExactly("key-0", "key-1", "key-2");
        assertThat(outboxEventRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("선점하면 소유자와 lease 만료 시각을 기록한다")
    void claim_recordsOwnerAndLease() {
        savePending(1, BASE);
        Instant now = BASE.plusSeconds(60);

        OutboxEvent claimed = outboxClaimer.claim(OWNER, 10, now).get(0);

        assertThat(claimed.getClaimOwner()).isEqualTo(OWNER);
        // application-test.yml 의 lease-duration 이 2초입니다.
        assertThat(claimed.getClaimExpiresAt()).isEqualTo(now.plusSeconds(2));
        // 선점은 발행 시도가 아니므로 attempts 를 올리지 않습니다.
        assertThat(claimed.getAttempts()).isZero();
        assertThat(claimed.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("다른 인스턴스가 보유한 유효한 lease는 가져오지 않는다")
    void claim_skipsRecordsWithValidLease() {
        savePending(2, BASE);
        Instant now = BASE.plusSeconds(60);

        List<OutboxEvent> first = outboxClaimer.claim(OWNER, 10, now);
        assertThat(first).hasSize(2);

        // lease 가 아직 유효한 시각입니다.
        List<OutboxEvent> second = outboxClaimer.claim(OTHER_OWNER, 10, now.plusSeconds(1));

        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("lease가 만료되면 다른 인스턴스가 회수한다")
    void claim_reclaimsExpiredLease() {
        savePending(1, BASE);
        Instant now = BASE.plusSeconds(60);

        outboxClaimer.claim(OWNER, 10, now);

        // lease 2초가 지난 시각입니다. relay 가 비정상 종료한 상황에 해당합니다.
        List<OutboxEvent> reclaimed = outboxClaimer.claim(OTHER_OWNER, 10, now.plusSeconds(3));

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).getClaimOwner()).isEqualTo(OTHER_OWNER);
        assertThat(reclaimed.get(0).getClaimExpiresAt()).isEqualTo(now.plusSeconds(5));
    }

    @Test
    @DisplayName("다음 시도 시각이 오지 않은 레코드는 선점하지 않는다")
    void claim_skipsRecordsNotYetDue() {
        outboxEventRepository.saveAndFlush(pending("future", BASE.plus(1, ChronoUnit.HOURS)));

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("발행 대기 상태가 아닌 레코드는 선점하지 않는다")
    void claim_skipsNonPendingRecords() {
        OutboxEvent saved = outboxEventRepository.saveAndFlush(pending("published", BASE));
        // 상태 전환은 #231 에서 붙으므로 아직 메서드가 없습니다. 스키마를 직접 바꿉니다.
        jdbcTemplate.update(
            "UPDATE outbox_events SET status = 'PUBLISHED' WHERE id = ?", saved.getId());

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).isEmpty();
    }

    /**
     * 두 worker 를 동시에 띄우고 각각 절반씩만 요청합니다.
     *
     * <p>batch 크기를 전체와 같게 두면 실행 순서에 따라 한쪽이 전부 가져가고 다른 쪽이 빈
     * 목록을 받습니다. lease 가 유효하니 정상 동작이지만, 그러면 겹치는지를 확인할 수 없습니다.
     * 절반씩 요청하면 두 worker 가 겹치든 순차로 실행되든 각각 5건을 받아야 합니다.
     *
     * <p>이 테스트가 확인하는 것은 두 worker 의 선점 집합이 서로 겹치지 않는다는 점입니다.
     * 잠금이 대기 없이 건너뛰는지(SKIP LOCKED)까지는 확인하지 않습니다. 그건 대기 시간으로
     * 판정해야 해서 결과가 환경에 따라 흔들립니다.
     */
    @Test
    @DisplayName("두 worker가 동시에 선점해도 같은 레코드를 중복으로 가져가지 않는다")
    void concurrentClaim_doesNotOverlap() throws Exception {
        savePending(10, BASE);
        Instant now = BASE.plusSeconds(60);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<OutboxEvent>> first = executor.submit(() -> {
                start.await();
                return outboxClaimer.claim(OWNER, 5, now);
            });
            Future<List<OutboxEvent>> second = executor.submit(() -> {
                start.await();
                return outboxClaimer.claim(OTHER_OWNER, 5, now);
            });

            start.countDown();

            List<UUID> firstIds = first.get(10, TimeUnit.SECONDS).stream()
                .map(OutboxEvent::getId).toList();
            List<UUID> secondIds = second.get(10, TimeUnit.SECONDS).stream()
                .map(OutboxEvent::getId).toList();

            assertThat(firstIds).hasSize(5);
            assertThat(secondIds).hasSize(5);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);

            // 소유자도 서로 다르게 기록돼야 합니다.
            assertThat(outboxEventRepository.findAllById(firstIds))
                .allMatch(event -> OWNER.equals(event.getClaimOwner()));
            assertThat(outboxEventRepository.findAllById(secondIds))
                .allMatch(event -> OTHER_OWNER.equals(event.getClaimOwner()));
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("batch 크기가 1보다 작으면 거부한다")
    void claim_rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> outboxClaimer.claim(OWNER, 0, BASE))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
