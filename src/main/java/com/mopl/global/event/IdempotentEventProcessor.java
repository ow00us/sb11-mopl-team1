package com.mopl.global.event;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka Consumer 가 같은 이벤트를 다시 받아도 도메인 부수 효과를 한 번만 수행하게 합니다.
 *
 * <p>사용법은 리스너가 이 처리기를 호출하고 실제 작업을 handler 로 넘기는 형태입니다.
 * AOP 애노테이션 대신 명시적 호출로 둔 이유는 트랜잭션 경계와 예외 전파가 코드에 보여야
 * 하기 때문입니다. 이 기능은 경계가 어긋나면 조용히 동작하지 않습니다.
 *
 * <pre>
 * &#64;KafkaListener(topics = ..., groupId = "mopl.something")
 * void onEvent(EventEnvelope envelope) {
 *     idempotentEventProcessor.process("mopl.something", envelope, () -> handle(envelope));
 * }
 * </pre>
 *
 * <p><b>알림 소비자는 이 처리기를 쓰지 않습니다.</b> 두 경계의 세밀도가 다릅니다. 이
 * 처리기는 {@code (consumerName, eventId)} 로 이벤트 단위이고, 알림은
 * {@code (source_event_id, receiver_id)} 로 수신자 단위입니다. 다수 수신자 fan-out 에서
 * 이벤트 단위 기록을 함께 쓰면, 일부 수신자만 저장된 상태에서 실패해 재시도할 때 이벤트가
 * 이미 처리된 것으로 판정되어 남은 수신자의 알림이 영구히 누락됩니다. 수신자 단위 분해가
 * 없고 도메인 테이블에 자연 유니크 키도 없는 Consumer 를 위한 기반입니다.
 */
@Slf4j
@Component
public class IdempotentEventProcessor {

    private final ProcessedEventRepository processedEventRepository;

    public IdempotentEventProcessor(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * 처리 이력이 없으면 handler 를 실행하고 처리 기록을 남깁니다.
     *
     * <p>전파를 {@code REQUIRES_NEW} 로 명시해 이 처리기가 쓰기 트랜잭션을 직접 소유합니다.
     * 호출부에 읽기 전용 트랜잭션이 있어도 이를 잠시 중단하므로, 처리 기록과 handler 의
     * 도메인 쓰기가 읽기 전용 속성을 상속하지 않습니다.
     *
     * <p>handler 의 도메인 변경과 처리 기록은 같은 트랜잭션에서 커밋됩니다. handler 가
     * 예외를 던지면 둘 다 롤백되어 Kafka 재시도가 같은 상태에서 다시 시작합니다.
     *
     * <p>처리 기록을 handler 보다 먼저 원자적으로 INSERT 합니다. 동시에 같은 이벤트가
     * 들어오면 한 트랜잭션만 선점하고 handler 를 실행합니다. 선점한 handler 가 실패하면
     * 처리 기록도 함께 롤백되므로 대기 중인 다른 트랜잭션이나 Kafka 재시도가 이어서 처리할
     * 수 있습니다.
     *
     * <p>handler 는 이 트랜잭션에 참여하는 데이터베이스 변경만 수행해야 합니다. 외부 API
     * 호출이나 메시지 발행처럼 트랜잭션으로 롤백할 수 없는 작업은 Outbox 로 분리합니다.
     *
     * @return handler 를 실행했으면 {@code true}, 이미 처리한 이벤트여서 건너뛰었으면 {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean process(String consumerName, EventEnvelope envelope, Runnable handler) {
        int inserted = processedEventRepository.insertIfAbsent(
            UUID.randomUUID(), consumerName, envelope.eventId(), envelope.type());

        if (inserted == 0) {
            log.debug("이미 처리한 이벤트를 건너뜁니다. consumer={}, eventId={}, type={}",
                consumerName, envelope.eventId(), envelope.type());
            return false;
        }

        handler.run();
        return true;
    }
}
