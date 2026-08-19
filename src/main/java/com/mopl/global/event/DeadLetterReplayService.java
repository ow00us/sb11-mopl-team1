package com.mopl.global.event;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * DLT 에 보존된 실패 이벤트를 원본 토픽으로 다시 보냅니다.
 *
 * <p>자동 재시도가 아닙니다. 공통 오류 처리의 재시도를 모두 소진하고 DLT 로 옮긴 레코드가
 * 대상이므로, 원인을 확인하지 않고 다시 보내면 같은 실패를 반복하며 DLT 만 늘어납니다.
 * 운영자가 좌표로 지목한 레코드 한 건만 다룹니다.
 *
 * <p>값과 키를 원본 바이트 그대로 보냅니다. eventId 와 partitionKey 가 유지되므로 소비자의
 * 멱등 판정과 파티션 내 순서가 replay 로 깨지지 않습니다. 이미 처리에 성공한 이벤트를 다시
 * 보내도 소비자가 걸러냅니다.
 *
 * <p>replay 는 DLT 레코드를 지우지 않습니다. 지울 수도 없지만, 남는 편이 맞습니다. 실패
 * 이력이 남아 있어야 무엇을 언제 다시 보냈는지 확인할 수 있습니다.
 */
@Slf4j
@Service
public class DeadLetterReplayService {

    private final DeadLetterInspector deadLetterInspector;
    private final KafkaTemplate<String, byte[]> replayKafkaTemplate;

    /** 원본 토픽 발행 확인을 기다리는 한도입니다. 확인 전에 성공으로 기록하면 안 됩니다. */
    private final Duration ackTimeout;

    public DeadLetterReplayService(
        DeadLetterInspector deadLetterInspector,
        @Qualifier("replayKafkaTemplate") KafkaTemplate<String, byte[]> replayKafkaTemplate,
        @Value("${mopl.kafka.dlt.replay-ack-timeout}") Duration ackTimeout
    ) {
        this.deadLetterInspector = deadLetterInspector;
        this.replayKafkaTemplate = replayKafkaTemplate;
        this.ackTimeout = ackTimeout;
    }

    /** DLT 레코드를 오래된 순으로 조회합니다. */
    public List<DeadLetterRecord> find(String deadLetterTopic, int limit) {
        return deadLetterInspector.find(deadLetterTopic, limit);
    }

    /**
     * 지목한 DLT 레코드 한 건을 원본 토픽으로 다시 보냅니다.
     *
     * @param deadLetterTopic DLT 이름
     * @param partition       DLT 파티션
     * @param offset          DLT offset
     * @return 다시 보낸 레코드의 정보
     * @throws EventContractViolationException 공통 계약의 토픽이 아닌 경우
     * @throws IllegalArgumentException        해당 좌표에 레코드가 없는 경우
     * @throws IllegalStateException           발행 확인을 받지 못한 경우
     */
    public DeadLetterRecord replay(String deadLetterTopic, int partition, long offset) {
        ConsumerRecord<String, byte[]> raw = deadLetterInspector
            .findRawAt(deadLetterTopic, partition, offset)
            .orElseThrow(() -> new IllegalArgumentException(
                "해당 좌표에 DLT 레코드가 없습니다. topic=%s, partition=%d, offset=%d"
                    .formatted(deadLetterTopic, partition, offset)));

        DeadLetterRecord record = deadLetterInspector.toDeadLetterRecord(raw);

        // 발행 대상은 헤더에서 읽은 값입니다. 헤더는 브로커에 저장된 데이터라 그대로 믿고
        // 발행하면 공통 계약 밖의 토픽으로 나갈 수 있습니다.
        MoplTopics.requireEventTopic(record.originalTopic());

        try {
            replayKafkaTemplate.send(record.originalTopic(), raw.key(), raw.value())
                .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DLT replay 발행이 중단되었습니다.", e);
        } catch (Exception e) {
            // DLT 레코드는 그대로 남습니다. 원인을 고친 뒤 같은 좌표로 다시 시도합니다.
            log.error("DLT replay 발행에 실패했습니다. dlt={}, partition={}, offset={}, eventId={}",
                deadLetterTopic, partition, offset, record.eventId(), e);
            throw new IllegalStateException("DLT replay 발행에 실패했습니다.", e);
        }

        log.info("DLT replay 발행을 마쳤습니다. target={}, eventId={}, key={}, dltOffset={}",
            record.originalTopic(), record.eventId(), record.partitionKey(), offset);
        return record;
    }
}
