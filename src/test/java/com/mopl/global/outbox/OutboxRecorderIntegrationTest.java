package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventContractViolationException;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.ProcessedEvent;
import com.mopl.global.event.ProcessedEventRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Outbox 기록 포트의 트랜잭션 경계를 실제 데이터베이스로 검증합니다.
 *
 * <p>이 기능의 값어치는 전부 트랜잭션 경계에 있습니다. 모킹하면 "저장 메서드를 호출했다"만
 * 확인되고, 도메인 변경과 함께 커밋·롤백되는지는 확인되지 않습니다.
 *
 * <p>테스트 클래스에 {@code @Transactional} 을 붙이지 않습니다. 붙이면 도메인 트랜잭션을
 * 테스트가 소유해 롤백 검증이 무의미해집니다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(OutboxRecorderIntegrationTest.DomainCaller.class)
class OutboxRecorderIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    OutboxRecorder outboxRecorder;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    DomainCaller domainCaller;

    @BeforeEach
    void clear() {
        outboxEventRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    private EventEnvelope envelope() {
        return envelope(UUID.randomUUID());
    }

    private EventEnvelope envelope(UUID eventId) {
        return new EventEnvelope(
            eventId,
            "follow.created",
            1,
            Instant.parse("2026-08-14T03:00:00Z"),
            UUID.randomUUID(),
            objectMapper.valueToTree(Map.of("followerId", "a", "followeeId", "b")));
    }

    @Test
    @DisplayName("도메인 변경과 Outbox 기록이 함께 커밋된다")
    void domainChangeAndRecord_commitTogether() {
        EventEnvelope event = envelope();

        domainCaller.changeDomainAndRecord(event, false);

        assertThat(outboxEventRepository.existsByEventId(event.eventId())).isTrue();
        assertThat(processedEventRepository.count()).isEqualTo(1);

        OutboxEvent recorded = outboxEventRepository.findByEventId(event.eventId()).orElseThrow();
        assertThat(recorded.getType()).isEqualTo("follow.created");
        assertThat(recorded.getVersion()).isEqualTo(1);
        assertThat(recorded.getAggregateId()).isEqualTo(event.aggregateId());
        assertThat(recorded.getOccurredAt()).isEqualTo(event.occurredAt());
        assertThat(recorded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recorded.getAttempts()).isZero();
        assertThat(recorded.getNextAttemptAt()).isNotNull();
    }

    @Test
    @DisplayName("도메인 변경이 롤백되면 Outbox 기록도 남지 않는다")
    void domainRollback_leavesNoRecord() {
        EventEnvelope event = envelope();

        assertThatThrownBy(() -> domainCaller.changeDomainAndRecord(event, true))
            .isInstanceOf(IllegalStateException.class);

        assertThat(outboxEventRepository.count()).isZero();
        assertThat(processedEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("Outbox 기록이 실패하면 도메인 변경도 커밋되지 않는다")
    void recordFailure_rollsBackDomainChange() {
        EventEnvelope event = envelope();

        // 같은 eventId 를 이미 기록해 둔 상태를 만듭니다.
        domainCaller.changeDomainAndRecord(event, false);
        assertThat(processedEventRepository.count()).isEqualTo(1);

        // 두 번째 호출은 eventId 유니크 제약에 걸립니다. 이때 그 트랜잭션의 도메인 변경도
        // 함께 사라져야 합니다.
        assertThatThrownBy(() -> domainCaller.changeDomainAndRecord(event, false))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("도메인 트랜잭션 없이 호출하면 기록을 거부한다")
    void withoutTransaction_isRejected() {
        EventEnvelope event = envelope();

        // REQUIRED 였다면 기록만 혼자 커밋됩니다. 도메인 변경이 뒤이어 실패해도 이벤트는
        // 남아, 일어나지 않은 일의 알림이 발행됩니다. 그래서 조용히 성공시키지 않습니다.
        assertThatThrownBy(() -> outboxRecorder.record(event, "key", "NONE"))
            .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("필수 필드가 없으면 계약 위반으로 거부하고 아무것도 남기지 않는다")
    void missingRequiredField_isRejected() {
        EventEnvelope noType = new EventEnvelope(
            UUID.randomUUID(), "  ", 1, Instant.parse("2026-08-14T03:00:00Z"),
            UUID.randomUUID(), objectMapper.valueToTree(Map.of("a", "b")));

        assertThatThrownBy(() -> domainCaller.changeDomainAndRecord(noType, false))
            .isInstanceOf(EventContractViolationException.class);

        assertThat(outboxEventRepository.count()).isZero();
        // 검증 실패도 도메인 변경을 되돌립니다.
        assertThat(processedEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("파티션 키나 순서 범위가 비면 거부한다")
    void missingRoutingInformation_isRejected() {
        EventEnvelope event = envelope();

        assertThatThrownBy(() -> domainCaller.recordWithRouting(event, " ", "NONE"))
            .isInstanceOf(EventContractViolationException.class);
        assertThatThrownBy(() -> domainCaller.recordWithRouting(event, "key", ""))
            .isInstanceOf(EventContractViolationException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("지원하지 않는 버전은 거부한다")
    void unsupportedVersion_isRejected() {
        EventEnvelope zeroVersion = new EventEnvelope(
            UUID.randomUUID(), "follow.created", 0, Instant.parse("2026-08-14T03:00:00Z"),
            UUID.randomUUID(), objectMapper.valueToTree(Map.of("a", "b")));

        assertThatThrownBy(() -> domainCaller.changeDomainAndRecord(zeroVersion, false))
            .isInstanceOf(EventContractViolationException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("envelope payload가 내용 손실 없이 기록된다")
    void payload_isRecordedWithoutLoss() throws Exception {
        EventEnvelope event = envelope();

        domainCaller.changeDomainAndRecord(event, false);

        String stored = outboxEventRepository.findByEventId(event.eventId()).orElseThrow()
            .getPayload();

        // 포트가 JsonNode 를 문자열로 바꿔 저장하므로 그 변환에서 내용이 바뀌지 않는지 봅니다.
        assertThat(objectMapper.readTree(stored)).isEqualTo(event.payload());
    }

    /**
     * 도메인 서비스 대역입니다.
     *
     * <p>도메인 변경 자리에는 {@code processed_events} 행을 씁니다. 외래 키가 없어 다른
     * 도메인을 끌어오지 않고도 "같은 트랜잭션에 묶인 쓰기"를 만들 수 있습니다.
     */
    static class DomainCaller {

        private final OutboxRecorder outboxRecorder;
        private final ProcessedEventRepository processedEventRepository;

        DomainCaller(OutboxRecorder outboxRecorder,
            ProcessedEventRepository processedEventRepository) {
            this.outboxRecorder = outboxRecorder;
            this.processedEventRepository = processedEventRepository;
        }

        @Transactional
        public void changeDomainAndRecord(EventEnvelope envelope, boolean failAfterRecord) {
            processedEventRepository.save(
                new ProcessedEvent("domain-change", UUID.randomUUID(), "domain"));

            outboxRecorder.record(envelope, "partition-key", "NONE");

            if (failAfterRecord) {
                throw new IllegalStateException("도메인 처리 실패");
            }
        }

        @Transactional
        public void recordWithRouting(EventEnvelope envelope, String partitionKey,
            String orderingScope) {
            outboxRecorder.record(envelope, partitionKey, orderingScope);
        }
    }
}
