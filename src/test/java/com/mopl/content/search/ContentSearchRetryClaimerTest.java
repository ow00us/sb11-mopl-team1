package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 선점과 lease 회수를 실제 데이터베이스로 검증합니다.
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} 동작은 실제 PostgreSQL에서만 확인됩니다. 모킹하면 두
 * 인스턴스가 같은 행을 가져가지 않는다는 성질이 검증되지 않습니다. OutboxClaimerIntegrationTest와
 * 같은 방식입니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(ContentSearchRetryClaimerTest.SecondClaimerConfig.class)
class ContentSearchRetryClaimerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final Instant BASE = Instant.parse("2026-08-20T03:00:00Z");

    @Autowired
    ContentSearchRetryClaimer contentSearchRetryClaimer;

    /**
     * "다른 인스턴스"를 흉내 내기 위한 두 번째 {@code ContentSearchRetryClaimer} 빈이다.
     *
     * <p>{@code new ContentSearchRetryClaimer(...)}로 직접 생성하면 Spring이 만든 게 아니라서
     * {@code @Transactional} AOP 프록시가 씌워지지 않는다 — 그러면 claimByIds()의 네이티브
     * UPDATE가 트랜잭션 없이 실행돼 {@code TransactionRequiredException}이 난다. 별도 빈으로
     * 등록해야 owner가 다른 진짜 프록시 인스턴스를 얻는다.
     */
    @Autowired
    @Qualifier("secondContentSearchRetryClaimer")
    ContentSearchRetryClaimer otherClaimer;

    @Autowired
    ContentSearchRetryRepository contentSearchRetryRepository;

    @TestConfiguration
    static class SecondClaimerConfig {
        @Bean
        ContentSearchRetryClaimer secondContentSearchRetryClaimer(
                ContentSearchRetryRepository contentSearchRetryRepository) {
            return new ContentSearchRetryClaimer(contentSearchRetryRepository);
        }
    }

    @BeforeEach
    void clear() {
        contentSearchRetryRepository.deleteAll();
    }

    private ContentSearchRetry pending(UUID contentId, Instant nextAttemptAt) {
        return new ContentSearchRetry(contentId, ContentSearchRetryEventType.SYNC, nextAttemptAt);
    }

    @Test
    @DisplayName("batch 크기만큼만 선점하고 다음 시도 시각이 이른 순으로 돌려준다")
    void claim_respectsBatchSizeAndOrder() {
        List<UUID> contentIds = List.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int i = 0; i < contentIds.size(); i++) {
            contentSearchRetryRepository.saveAndFlush(pending(contentIds.get(i), BASE.plusSeconds(i)));
        }

        List<ContentSearchRetry> claimed = contentSearchRetryClaimer.claim(3, BASE.plusSeconds(60));

        assertThat(claimed).hasSize(3);
        assertThat(claimed).extracting(ContentSearchRetry::getContentId)
                .containsExactly(contentIds.get(0), contentIds.get(1), contentIds.get(2));
        assertThat(contentSearchRetryRepository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("선점하면 소유자와 lease 만료 시각을 기록한다")
    void claim_recordsOwnerAndLease() {
        contentSearchRetryRepository.saveAndFlush(pending(UUID.randomUUID(), BASE));
        Instant now = BASE.plusSeconds(60);

        ContentSearchRetry claimed = contentSearchRetryClaimer.claim(10, now).get(0);

        assertThat(claimed.getClaimOwner()).isNotBlank();
        // ContentSearchRetryClaimer의 LEASE_DURATION은 1분으로 고정돼 있다.
        assertThat(claimed.getClaimExpiresAt()).isEqualTo(now.plusSeconds(60));
        assertThat(claimed.getAttempts()).isZero();
        assertThat(claimed.getStatus()).isEqualTo(ContentSearchRetryStatus.PENDING);
    }

    @Test
    @DisplayName("다른 인스턴스가 보유한 유효한 lease는 가져오지 않는다")
    void claim_skipsRecordsWithValidLease() {
        contentSearchRetryRepository.saveAndFlush(pending(UUID.randomUUID(), BASE));
        Instant now = BASE.plusSeconds(60);

        List<ContentSearchRetry> first = contentSearchRetryClaimer.claim(10, now);
        assertThat(first).hasSize(1);

        List<ContentSearchRetry> second = otherClaimer.claim(10, now.plusSeconds(1));

        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("lease가 만료되면 다른 인스턴스가 회수한다")
    void claim_reclaimsExpiredLease() {
        contentSearchRetryRepository.saveAndFlush(pending(UUID.randomUUID(), BASE));
        Instant now = BASE.plusSeconds(60);

        ContentSearchRetry firstClaimed = contentSearchRetryClaimer.claim(10, now).get(0);

        // lease(1분)가 지난 시각이다. 재시도 스케줄러가 비정상 종료한 상황에 해당한다.
        List<ContentSearchRetry> reclaimed = otherClaimer.claim(10, now.plusSeconds(61));

        assertThat(reclaimed).hasSize(1);
        assertThat(reclaimed.get(0).getClaimOwner()).isNotEqualTo(firstClaimed.getClaimOwner());
    }

    @Test
    @DisplayName("다음 시도 시각이 오지 않은 레코드는 선점하지 않는다")
    void claim_skipsRecordsNotYetDue() {
        contentSearchRetryRepository.saveAndFlush(pending(UUID.randomUUID(), BASE.plus(1, ChronoUnit.HOURS)));

        List<ContentSearchRetry> claimed = contentSearchRetryClaimer.claim(10, BASE.plusSeconds(60));

        assertThat(claimed).isEmpty();
    }

    @Test
    @DisplayName("PENDING 상태가 아닌 레코드는 선점하지 않는다")
    void claim_skipsNonPendingRecords() {
        ContentSearchRetry retry = pending(UUID.randomUUID(), BASE);
        retry.markCompleted();
        contentSearchRetryRepository.saveAndFlush(retry);

        List<ContentSearchRetry> claimed = contentSearchRetryClaimer.claim(10, BASE.plusSeconds(60));

        assertThat(claimed).isEmpty();
    }

    /**
     * 두 인스턴스를 동시에 띄우고 각각 절반씩만 요청한다. OutboxClaimerIntegrationTest의
     * concurrentClaim_doesNotOverlap과 같은 이유·같은 구조다.
     */
    @Test
    @DisplayName("두 인스턴스가 동시에 선점해도 같은 레코드를 중복으로 가져가지 않는다")
    void concurrentClaim_doesNotOverlap() throws Exception {
        List<UUID> contentIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID contentId = UUID.randomUUID();
            contentIds.add(contentId);
            contentSearchRetryRepository.saveAndFlush(pending(contentId, BASE.plusSeconds(i)));
        }
        Instant now = BASE.plusSeconds(60);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<ContentSearchRetry>> first = executor.submit(() -> {
                start.await();
                return contentSearchRetryClaimer.claim(5, now);
            });
            Future<List<ContentSearchRetry>> second = executor.submit(() -> {
                start.await();
                return otherClaimer.claim(5, now);
            });

            start.countDown();

            List<UUID> firstIds = first.get(10, TimeUnit.SECONDS).stream()
                    .map(ContentSearchRetry::getId).toList();
            List<UUID> secondIds = second.get(10, TimeUnit.SECONDS).stream()
                    .map(ContentSearchRetry::getId).toList();

            assertThat(firstIds).hasSize(5);
            assertThat(secondIds).hasSize(5);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        } finally {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
