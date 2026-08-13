package com.mopl.global.event;

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
     * <p>전파를 {@code REQUIRED} 로 명시합니다. 이 저장소는 서비스 대부분이 클래스 레벨
     * {@code @Transactional(readOnly = true)} 를 두는데, 그 트랜잭션에 참여하면 FlushMode 가
     * MANUAL 이라 처리 기록 INSERT 가 예외 없이 사라집니다. 그러면 중복 차단이 동작하지
     * 않으면서 아무 오류도 나지 않습니다. 호출부가 readOnly 컨텍스트를 만들지 않도록
     * 리스너에서 직접 호출하는 것을 전제로 합니다.
     *
     * <p>handler 의 도메인 변경과 처리 기록은 같은 트랜잭션에서 커밋됩니다. handler 가
     * 예외를 던지면 둘 다 롤백되어 Kafka 재시도가 같은 상태에서 다시 시작합니다.
     *
     * <p>동시에 같은 이벤트가 들어오면 사전 조회를 둘 다 통과할 수 있습니다. 이때는 커밋
     * 시점에 유니크 제약이 한쪽을 거부하고, 거부된 쪽은 handler 작업까지 롤백됩니다.
     * 이어지는 Kafka 재시도는 승자가 남긴 기록을 보고 건너뜁니다. 결과적으로 부수 효과는
     * 한 번만 남습니다. 사전 조회는 정상 흐름에서 예외를 피하기 위한 최적화이고, 정확성은
     * 유니크 제약이 보장합니다.
     *
     * @return handler 를 실행했으면 {@code true}, 이미 처리한 이벤트여서 건너뛰었으면 {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean process(String consumerName, EventEnvelope envelope, Runnable handler) {
        if (processedEventRepository.existsByConsumerNameAndEventId(consumerName, envelope.eventId())) {
            log.debug("이미 처리한 이벤트를 건너뜁니다. consumer={}, eventId={}, type={}",
                consumerName, envelope.eventId(), envelope.type());
            return false;
        }

        handler.run();

        // flush 를 미루지 않습니다. 유니크 제약 위반이 커밋 시점이 아니라 이 지점에서
        // 드러나 스택 트레이스가 원인을 가리킵니다. 어차피 롤백되므로 영속성 컨텍스트가
        // 무효화되는 것은 문제가 되지 않습니다.
        processedEventRepository.saveAndFlush(
            new ProcessedEvent(consumerName, envelope.eventId(), envelope.type()));

        return true;
    }
}
