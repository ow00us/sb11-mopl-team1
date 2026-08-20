package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
            // 같은 키로 여러 건을 만드는 케이스가 있어 사건 식별자를 따로 둡니다.
            "follow.created:" + UUID.randomUUID(), nextAttemptAt);
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

    // 순서 게이트 (계약 §9)

    /** 같은 키에 순서를 선언한 이벤트를 발생 순으로 만듭니다. */
    private List<OutboxEvent> saveOrderedPair(String partitionKey) {
        OutboxEvent first = ordered(partitionKey, BASE, "first");
        OutboxEvent second = ordered(partitionKey, BASE.plusSeconds(1), "second");
        return outboxEventRepository.saveAllAndFlush(List.of(first, second));
    }

    private OutboxEvent ordered(String partitionKey, Instant occurredAt, String suffix) {
        return new OutboxEvent(
            UUID.randomUUID(), "premiere.upcoming", 1, UUID.randomUUID(), occurredAt,
            "{}", partitionKey, "AGGREGATE",
            "premiere.upcoming:" + partitionKey + ":" + suffix, occurredAt);
    }

    private void setStatus(OutboxEvent event, String status) {
        jdbcTemplate.update(
            "UPDATE outbox_events SET status = ? WHERE id = ?", status, event.getId());
    }

    /**
     * 앞선 이벤트가 아직 나가지 않았으면 뒤 이벤트를 선점하지 않습니다.
     *
     * <p>이 조건이 없으면 앞선 이벤트가 실패해 재시도 대기로 밀릴 때 뒤 이벤트가 먼저
     * 나갑니다. {@code AGGREGATE} 인 이벤트에는 그 순서가 계약입니다.
     */
    @Test
    @DisplayName("같은 키에 앞선 발행 대기 이벤트가 있으면 뒤 이벤트를 선점하지 않는다")
    void claim_gatesOnEarlierPendingInSameKey() {
        List<OutboxEvent> pair = saveOrderedPair("agg-1");

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).extracting(OutboxEvent::getId).containsExactly(pair.get(0).getId());
    }

    @Test
    @DisplayName("앞선 이벤트가 발행을 마치면 뒤 이벤트를 선점한다")
    void claim_releasesGateWhenEarlierPublished() {
        List<OutboxEvent> pair = saveOrderedPair("agg-1");
        setStatus(pair.get(0), "PUBLISHED");

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).extracting(OutboxEvent::getId).containsExactly(pair.get(1).getId());
    }

    /**
     * 계약은 최종 실패가 후속을 계속 차단하도록 정합니다. 사람이 재처리하거나 전달 시한을
     * 넘긴 것으로 넘겨야 뒤 이벤트가 진행합니다.
     */
    @Test
    @DisplayName("앞선 이벤트가 최종 실패면 뒤 이벤트를 계속 막는다")
    void claim_keepsGateWhenEarlierFailed() {
        List<OutboxEvent> pair = saveOrderedPair("agg-1");
        setStatus(pair.get(0), "FAILED");

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("앞선 이벤트가 전달 시한을 넘긴 상태면 뒤 이벤트를 선점한다")
    void claim_releasesGateWhenEarlierExpired() {
        List<OutboxEvent> pair = saveOrderedPair("agg-1");
        setStatus(pair.get(0), "EXPIRED");

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).extracting(OutboxEvent::getId).containsExactly(pair.get(1).getId());
    }

    /**
     * 계약은 {@code NONE} 에 선행 이벤트 게이트를 적용하지 않습니다. 선후 관계가 없다고 선언한
     * 이벤트를 같은 키를 쓴다는 이유로 세우면 처리량만 떨어집니다.
     */
    @Test
    @DisplayName("orderingScope가 NONE이면 같은 키라도 함께 선점한다")
    void claim_doesNotGateNoneScope() {
        outboxEventRepository.saveAllAndFlush(List.of(
            pending("shared-key", BASE), pending("shared-key", BASE.plusSeconds(1))));

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).hasSize(2);
    }

    /**
     * 막는 쪽도 {@code NONE} 은 제외합니다. 순서를 선언하지 않은 이벤트가 같은 키를 쓴다는
     * 이유로 다른 이벤트를 세울 수는 없습니다.
     */
    @Test
    @DisplayName("앞선 이벤트가 NONE이면 뒤의 순서 이벤트를 막지 않는다")
    void claim_noneScopeDoesNotBlockOthers() {
        outboxEventRepository.saveAndFlush(pending("agg-1", BASE));
        OutboxEvent later = outboxEventRepository.saveAndFlush(
            ordered("agg-1", BASE.plusSeconds(1), "later"));

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).extracting(OutboxEvent::getId).contains(later.getId());
    }

    @Test
    @DisplayName("다른 키의 이벤트는 서로 막지 않는다")
    void claim_gatesPerPartitionKey() {
        saveOrderedPair("agg-1");
        saveOrderedPair("agg-2");

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).hasSize(2);
        assertThat(claimed).extracting(OutboxEvent::getPartitionKey)
            .containsExactlyInAnyOrder("agg-1", "agg-2");
    }

    /**
     * 발생 시각이 같으면 id 가 순서를 가릅니다. 시각만 비교하면 두 건이 서로를 막지 않아 둘 다
     * 나가고, 그때 순서는 정해지지 않습니다.
     *
     * <p>기대값을 {@code UUID#compareTo} 로 고르지 않습니다. 그 비교는 상위 64비트를 부호 있는
     * long 으로 보는데, PostgreSQL 의 uuid 비교는 16바이트를 부호 없이 봅니다. 최상위 비트가
     * 선 값이 섞이면 두 순서가 갈립니다. 표준 표기 문자열 비교가 데이터베이스 쪽과 같습니다.
     */
    @Test
    @DisplayName("발생 시각이 같으면 id 순으로 하나만 선점한다")
    void claim_gatesOnIdWhenOccurredAtTies() {
        List<OutboxEvent> saved = outboxEventRepository.saveAllAndFlush(List.of(
            ordered("agg-1", BASE, "a"), ordered("agg-1", BASE, "b")));

        List<OutboxEvent> claimed = outboxClaimer.claim(OWNER, 10, BASE.plusSeconds(60));

        assertThat(claimed).hasSize(1);
        UUID expected = saved.stream().map(OutboxEvent::getId)
            .min(Comparator.comparing(UUID::toString)).orElseThrow();
        assertThat(claimed.get(0).getId()).isEqualTo(expected);
    }
}
