package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
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
        assertThatThrownBy(() -> outboxRecorder.record(event, "key", "NONE", "follow.created:key"))
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
    @DisplayName("payload가 JSON null이면 거부한다")
    void nullNodePayload_isRejected() {
        // JsonNode 는 값이 없어도 참조가 null 이 아닙니다. NullNode 를 통과시키면
        // toString() 이 "null" 이라 jsonb 에 JSON null 이 저장됩니다. 컬럼이 NOT NULL 이어도
        // SQL NULL 이 아니므로 스키마가 막지 못합니다.
        EventEnvelope nullPayload = new EventEnvelope(
            UUID.randomUUID(), "follow.created", 1, Instant.parse("2026-08-14T03:00:00Z"),
            UUID.randomUUID(), objectMapper.nullNode());

        assertThatThrownBy(() -> domainCaller.changeDomainAndRecord(nullPayload, false))
            .isInstanceOf(EventContractViolationException.class);

        assertThat(outboxEventRepository.count()).isZero();
        assertThat(processedEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("payload가 MissingNode면 거부한다")
    void missingNodePayload_isRejected() {
        // MissingNode 는 toString() 이 빈 문자열이라 jsonb 파싱에 실패합니다. 저장 시점에야
        // 예외가 나서 원인이 흐려지므로 기록 전에 막습니다.
        EventEnvelope missingPayload = new EventEnvelope(
            UUID.randomUUID(), "follow.created", 1, Instant.parse("2026-08-14T03:00:00Z"),
            UUID.randomUUID(), MissingNode.getInstance());

        assertThatThrownBy(() -> domainCaller.changeDomainAndRecord(missingPayload, false))
            .isInstanceOf(EventContractViolationException.class);

        assertThat(outboxEventRepository.count()).isZero();
        assertThat(processedEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("빈 객체 payload는 허용한다")
    void emptyObjectPayload_isAllowed() {
        // 담을 도메인 사실이 없는 이벤트도 있습니다. 값이 없는 것과 빈 객체는 다릅니다.
        EventEnvelope emptyPayload = new EventEnvelope(
            UUID.randomUUID(), "follow.created", 1, Instant.parse("2026-08-14T03:00:00Z"),
            UUID.randomUUID(), objectMapper.createObjectNode());

        domainCaller.changeDomainAndRecord(emptyPayload, false);

        assertThat(outboxEventRepository.findByEventId(emptyPayload.eventId()).orElseThrow()
            .getPayload()).isEqualTo("{}");
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
    @Test
    @DisplayName("기록한 이벤트에 중복 판정 키가 함께 저장된다")
    void record_storesDeduplicationKey() {
        EventEnvelope event = envelope();
        String deduplicationKey = "follow.created:" + UUID.randomUUID();

        domainCaller.recordWithDeduplicationKey(event, deduplicationKey);

        OutboxEvent saved = outboxEventRepository.findByEventId(event.eventId()).orElseThrow();
        assertThat(saved.getDeduplicationKey()).isEqualTo(deduplicationKey);
    }

    /**
     * 같은 사건이 두 번 기록되는 경우입니다.
     *
     * <p>envelope 를 각각 새로 만들면 {@code eventId} 가 서로 달라 event_id 유니크로는 막히지
     * 않습니다. 이 경우를 막는 것이 중복 판정 키의 목적입니다.
     */
    @Test
    @DisplayName("eventId가 달라도 같은 중복 판정 키의 두 번째 기록은 거부한다")
    void duplicateDeduplicationKey_isRejected() {
        String deduplicationKey = "follow.created:" + UUID.randomUUID();
        EventEnvelope first = envelope();
        EventEnvelope second = envelope();

        assertThat(first.eventId()).isNotEqualTo(second.eventId());

        domainCaller.recordWithDeduplicationKey(first, deduplicationKey);

        assertThatThrownBy(() -> domainCaller.recordWithDeduplicationKey(second, deduplicationKey))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.findByEventId(first.eventId())).isPresent();
        assertThat(outboxEventRepository.findByEventId(second.eventId())).isEmpty();
    }

    /**
     * 거부가 도메인 트랜잭션까지 되돌리는지 확인합니다.
     *
     * <p>한 트랜잭션에서 두 번 기록하고 두 번째가 거부되면, 앞서 성공한 첫 번째도 남지 않아야
     * 합니다. 남는다면 거부를 잡아 삼킨 것이거나 기록이 별도 트랜잭션에서 커밋된 것입니다.
     */
    @Test
    @DisplayName("중복 판정 키가 거부되면 같은 트랜잭션의 앞선 기록도 남지 않는다")
    void duplicateDeduplicationKey_rollsBackDomainTransaction() {
        String deduplicationKey = "follow.created:" + UUID.randomUUID();

        assertThatThrownBy(() ->
            domainCaller.recordTwice(envelope(), envelope(), deduplicationKey))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("중복 판정 키가 비어 있으면 계약 위반으로 거부한다")
    void blankDeduplicationKey_isRejected() {
        EventEnvelope event = envelope();

        assertThatThrownBy(() -> domainCaller.recordWithDeduplicationKey(event, "  "))
            .isInstanceOf(EventContractViolationException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

    /**
     * 컬럼 상한을 넘기면 저장 시점에 데이터베이스 오류로 드러나는데, 그 메시지는 어느 값이
     * 문제인지 알려주지 않습니다.
     */
    @Test
    @DisplayName("중복 판정 키가 200자를 넘으면 계약 위반으로 거부한다")
    void tooLongDeduplicationKey_isRejected() {
        EventEnvelope event = envelope();
        String tooLong = "follow.created:" + "x".repeat(200);

        assertThatThrownBy(() -> domainCaller.recordWithDeduplicationKey(event, tooLong))
            .isInstanceOf(EventContractViolationException.class);

        assertThat(outboxEventRepository.count()).isZero();
    }

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

            outboxRecorder.record(envelope, "partition-key", "NONE",
                envelope.type() + ":" + envelope.aggregateId());

            if (failAfterRecord) {
                throw new IllegalStateException("도메인 처리 실패");
            }
        }

        @Transactional
        public void recordWithRouting(EventEnvelope envelope, String partitionKey,
            String orderingScope) {
            outboxRecorder.record(envelope, partitionKey, orderingScope,
                envelope.type() + ":" + envelope.aggregateId());
        }

        /** 중복 판정 키를 테스트가 직접 정하는 경로입니다. */
        @Transactional
        public void recordWithDeduplicationKey(EventEnvelope envelope, String deduplicationKey) {
            outboxRecorder.record(envelope, "partition-key", "NONE", deduplicationKey);
        }

        /**
         * 한 트랜잭션에서 두 번 기록합니다.
         *
         * <p>두 번째가 거부될 때 첫 번째까지 함께 사라지는지 확인하는 데 씁니다.
         */
        @Transactional
        public void recordTwice(
            EventEnvelope first, EventEnvelope second, String deduplicationKey
        ) {
            outboxRecorder.record(first, "partition-key", "NONE", deduplicationKey);
            outboxRecorder.record(second, "partition-key", "NONE", deduplicationKey);
        }
    }
}
