package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 멱등 처리 경계를 실제 데이터베이스로 검증합니다.
 *
 * <p>리포지토리를 모킹하지 않는 것이 핵심입니다. 이 기능은 트랜잭션 경계가 어긋나면
 * INSERT 가 예외 없이 사라지면서도 모킹 기반 테스트는 통과합니다. 실제로 커밋되는지
 * 확인해야 의미가 있습니다.
 *
 * <p>테스트 클래스에 {@code @Transactional} 을 붙이지 않습니다. 붙이면 처리기의
 * 트랜잭션이 테스트 트랜잭션에 참여해 롤백 동작을 검증할 수 없습니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(IdempotentEventProcessorIntegrationTest.ReadOnlyCaller.class)
class IdempotentEventProcessorIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final String CONSUMER = "mopl.test-consumer";
    private static final String OTHER_CONSUMER = "mopl.other-consumer";

    @Autowired
    IdempotentEventProcessor processor;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ReadOnlyCaller readOnlyCaller;

    @BeforeEach
    void clearRecords() {
        processedEventRepository.deleteAll();
    }

    private EventEnvelope envelope() {
        return new EventEnvelope(
            UUID.randomUUID(),
            "follow.created",
            1,
            Instant.parse("2026-08-13T03:00:00Z"),
            UUID.randomUUID(),
            objectMapper.valueToTree(Map.of("followerId", "a", "followeeId", "b")));
    }

    @Test
    @DisplayName("최초 이벤트는 handler를 실행하고 처리 기록을 남긴다")
    void firstEvent_runsHandlerAndRecords() {
        EventEnvelope event = envelope();
        AtomicInteger runs = new AtomicInteger();

        boolean processed = processor.process(CONSUMER, event, runs::incrementAndGet);

        assertThat(processed).isTrue();
        assertThat(runs.get()).isEqualTo(1);
        assertThat(processedEventRepository.existsByConsumerNameAndEventId(CONSUMER, event.eventId()))
            .isTrue();
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 Consumer가 같은 eventId를 다시 받으면 handler를 실행하지 않는다")
    void duplicateEvent_skipsHandler() {
        EventEnvelope event = envelope();
        AtomicInteger runs = new AtomicInteger();

        processor.process(CONSUMER, event, runs::incrementAndGet);
        boolean processedAgain = processor.process(CONSUMER, event, runs::incrementAndGet);

        assertThat(processedAgain).isFalse();
        assertThat(runs.get()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 Consumer는 같은 eventId를 각각 한 번씩 처리한다")
    void differentConsumers_eachProcessOnce() {
        EventEnvelope event = envelope();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        assertThat(processor.process(CONSUMER, event, first::incrementAndGet)).isTrue();
        assertThat(processor.process(OTHER_CONSUMER, event, second::incrementAndGet)).isTrue();

        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(2);

        // 각 Consumer 의 두 번째 수신은 각각 건너뜁니다.
        assertThat(processor.process(CONSUMER, event, first::incrementAndGet)).isFalse();
        assertThat(processor.process(OTHER_CONSUMER, event, second::incrementAndGet)).isFalse();
        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("handler가 실패하면 처리 기록도 남지 않아 재시도가 가능하다")
    void handlerFailure_leavesNoRecord_andRetrySucceeds() {
        EventEnvelope event = envelope();
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> processor.process(CONSUMER, event, () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("일시적인 DB 오류");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(processedEventRepository.count()).isZero();
        assertThat(processedEventRepository.existsByConsumerNameAndEventId(CONSUMER, event.eventId()))
            .isFalse();

        // Kafka 재시도를 모사합니다. 기록이 없으므로 handler 가 다시 실행됩니다.
        boolean retried = processor.process(CONSUMER, event, runs::incrementAndGet);

        assertThat(retried).isTrue();
        assertThat(runs.get()).isEqualTo(2);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("handler의 쓰기와 처리 기록은 같은 트랜잭션에서 커밋되고 함께 롤백된다")
    void handlerWriteAndRecord_shareTransaction() {
        EventEnvelope event = envelope();

        // handler 가 다른 consumerName 으로 행을 하나 더 넣습니다. 도메인 테이블을 끌어오지
        // 않고 handler 쪽 쓰기가 같은 트랜잭션에 묶이는지 확인하기 위한 대역입니다.
        assertThatThrownBy(() -> processor.process(CONSUMER, event, () -> {
            processedEventRepository.saveAndFlush(
                new ProcessedEvent("handler-write", event.eventId(), event.type()));
            throw new IllegalStateException("handler 실패");
        })).isInstanceOf(IllegalStateException.class);

        // handler 의 쓰기와 처리 기록 모두 사라져야 합니다.
        assertThat(processedEventRepository.count()).isZero();

        // 성공 경로에서는 둘 다 남습니다.
        boolean processed = processor.process(CONSUMER, event, () ->
            processedEventRepository.saveAndFlush(
                new ProcessedEvent("handler-write", event.eventId(), event.type())));

        assertThat(processed).isTrue();
        assertThat(processedEventRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("스키마 유니크 제약이 중복 처리 기록을 거부한다")
    void uniqueConstraint_rejectsDuplicateRecord() {
        EventEnvelope event = envelope();
        processor.process(CONSUMER, event, () -> {
        });

        // 처리기를 우회해 같은 (consumerName, eventId) 를 직접 저장해도 스키마가 거부합니다.
        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(
            new ProcessedEvent(CONSUMER, event.eventId(), event.type())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("읽기 전용 호출부가 있어도 독립된 쓰기 트랜잭션에서 처리한다")
    void readOnlyCaller_doesNotLeakReadOnlyTransaction() {
        EventEnvelope event = envelope();
        AtomicInteger runs = new AtomicInteger();

        boolean processed = readOnlyCaller.process(event, runs::incrementAndGet);

        assertThat(processed).isTrue();
        assertThat(runs.get()).isEqualTo(1);
        assertThat(processedEventRepository.existsByConsumerNameAndEventId(CONSUMER, event.eventId()))
            .isTrue();
    }

    @Test
    @DisplayName("동일 이벤트를 동시에 받아도 한 handler만 실행한다")
    void concurrentDuplicate_executesOnlyOneHandler() throws Exception {
        EventEnvelope event = envelope();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstHandlerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstHandler = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> processor.process(CONSUMER, event, () -> {
                runs.incrementAndGet();
                firstHandlerEntered.countDown();
                awaitLatch(releaseFirstHandler);
            }));

            assertThat(firstHandlerEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Boolean> second = executor.submit(() -> {
                secondStarted.countDown();
                return processor.process(CONSUMER, event, runs::incrementAndGet);
            });

            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitSecondClaimBlocked();
            releaseFirstHandler.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(runs.get()).isEqualTo(1);
            assertThat(processedEventRepository.count()).isEqualTo(1);
        } finally {
            releaseFirstHandler.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitSecondClaimBlocked() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer blockedClaims = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_stat_activity
                WHERE wait_event_type = 'Lock'
                  AND LOWER(query) LIKE '%insert into processed_events%'
                """, Integer.class);
            if (blockedClaims != null && blockedClaims > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("두 번째 처리 요청이 유니크 키 선점 대기 상태에 도달하지 않았습니다.");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 동기화 대기 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    public static class ReadOnlyCaller {

        private final IdempotentEventProcessor processor;

        public ReadOnlyCaller(IdempotentEventProcessor processor) {
            this.processor = processor;
        }

        @Transactional(readOnly = true)
        public boolean process(EventEnvelope event, Runnable handler) {
            return processor.process(CONSUMER, event, handler);
        }
    }
}
