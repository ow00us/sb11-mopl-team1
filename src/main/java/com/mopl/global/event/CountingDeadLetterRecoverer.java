package com.mopl.global.event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

/**
 * DLT 발행을 위임하면서 레코드별 연속 실패 횟수를 셉니다.
 *
 * <p>카운팅을 오류 핸들러가 아니라 recoverer 에 두는 이유가 있습니다. 오류 핸들러가
 * 복구 단계의 예외를 밖으로 던지는지 삼키는지는 spring-kafka 내부 구현에 달려 있어,
 * 핸들러의 catch 만 믿으면 조용히 동작하지 않을 수 있습니다. 발행이 실제로 실패하는
 * 지점은 여기 하나뿐이므로 여기서 세는 것이 확실합니다.
 *
 * <p>카운터는 레코드 식별자별로 관리합니다. 하나의 카운터를 공유하면 서로 다른
 * 파티션이나 리스너의 실패가 번갈아 발생할 때 카운트가 계속 초기화되어 임계값에
 * 도달하지 못합니다.
 *
 * <p>맵은 발행 성공 시 해당 키를 지웁니다. offset 은 성공했을 때만 전진하므로
 * 파티션당 살아 있는 항목은 사실상 하나입니다.
 */
public class CountingDeadLetterRecoverer implements ConsumerRecordRecoverer {

    private final ConsumerRecordRecoverer delegate;
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    public CountingDeadLetterRecoverer(ConsumerRecordRecoverer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        try {
            delegate.accept(record, exception);
            consecutiveFailures.remove(recordKey(record));
        } catch (RuntimeException dltFailure) {
            consecutiveFailures.merge(recordKey(record), 1, Integer::sum);
            throw dltFailure;
        }
    }

    /** 해당 레코드의 DLT 발행이 연속으로 몇 번 실패했는지 반환합니다. */
    public int failureCount(ConsumerRecord<?, ?> record) {
        return consecutiveFailures.getOrDefault(recordKey(record), 0);
    }

    /** 더 이상 추적하지 않을 레코드의 카운트를 제거합니다. */
    public void forget(ConsumerRecord<?, ?> record) {
        consecutiveFailures.remove(recordKey(record));
    }

    static String recordKey(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }
}
