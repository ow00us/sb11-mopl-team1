package com.mopl.global.outbox;

import com.mopl.global.event.EventContractViolationException;
import com.mopl.global.event.EventEnvelope;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * {@link OutboxRecorder} 의 공통 저장 구현입니다.
 */
@Slf4j
@Component
public class OutboxRecorderImpl implements OutboxRecorder {

    /** 현재 지원하는 최소 envelope 버전입니다. 계약상 최초 버전이 1 입니다. */
    private static final int MINIMUM_SUPPORTED_VERSION = 1;

    private final OutboxEventRepository outboxEventRepository;

    public OutboxRecorderImpl(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * 호출자가 시작한 도메인 트랜잭션에 참여합니다.
     *
     * <p>전파를 {@code MANDATORY} 로 둔 것이 이 클래스의 핵심입니다. {@code REQUIRED} 로 두면
     * 트랜잭션 없이 호출해도 기록이 혼자 커밋됩니다. 도메인 변경이 뒤이어 실패해도 이벤트만
     * 남아, Outbox 가 막으려던 것과 정반대인 "일어나지 않은 일의 알림"이 발행됩니다. 그런
     * 호출은 조용히 성공하는 대신 즉시 실패해야 합니다.
     *
     * <p>같은 트랜잭션이므로 도메인 변경이 롤백되면 기록도 남지 않고, 기록이 실패하면 도메인
     * 변경도 커밋되지 않습니다.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(EventEnvelope envelope, String partitionKey, String orderingScope) {
        validate(envelope, partitionKey, orderingScope);

        Instant recordedAt = Instant.now();

        // flush 를 미루지 않습니다. eventId 중복 같은 제약 위반이 커밋 시점이 아니라 이 지점에서
        // 드러나 스택 트레이스가 원인을 가리킵니다. 어차피 트랜잭션 전체가 롤백됩니다.
        outboxEventRepository.saveAndFlush(new OutboxEvent(
            envelope.eventId(),
            envelope.type(),
            envelope.version(),
            envelope.aggregateId(),
            envelope.occurredAt(),
            envelope.payload().toString(),
            partitionKey,
            orderingScope,
            // 기록 즉시 발행 대상이 되도록 둡니다. 재시도 지연은 relay 가 정합니다.
            recordedAt));

        log.debug("Outbox 기록. eventId={}, type={}, partitionKey={}",
            envelope.eventId(), envelope.type(), partitionKey);
    }

    /**
     * 기록 전에 계약을 확인합니다.
     *
     * <p>여기서 걸러내지 않으면 잘못된 이벤트가 커밋된 뒤 relay 와 소비자까지 흘러갑니다.
     * 그 시점에는 되돌릴 수 없고 DLT 에서 원인을 되짚어야 합니다. 기록 시점이 가장 싸게
     * 막을 수 있는 자리입니다.
     */
    private void validate(EventEnvelope envelope, String partitionKey, String orderingScope) {
        if (envelope == null) {
            throw new EventContractViolationException("envelope 이 null 입니다.");
        }
        requirePresent(envelope.eventId(), "eventId");
        requirePresent(envelope.aggregateId(), "aggregateId");
        requirePresent(envelope.occurredAt(), "occurredAt");
        requirePresent(envelope.payload(), "payload");
        requireText(envelope.type(), "type");
        requireText(partitionKey, "partitionKey");
        requireText(orderingScope, "orderingScope");

        if (envelope.version() < MINIMUM_SUPPORTED_VERSION) {
            throw new EventContractViolationException(
                "지원하지 않는 envelope version 입니다. 최소 " + MINIMUM_SUPPORTED_VERSION
                    + ", 실제 " + envelope.version());
        }
    }

    private void requirePresent(Object value, String field) {
        if (value == null) {
            throw new EventContractViolationException(field + " 이(가) 없습니다.");
        }
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new EventContractViolationException(field + " 이(가) 비어 있습니다.");
        }
    }
}
