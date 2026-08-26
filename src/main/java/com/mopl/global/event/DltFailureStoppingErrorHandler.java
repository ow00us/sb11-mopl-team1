package com.mopl.global.event;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;

/**
 * DLT 발행이 같은 레코드에서 반복 실패하면 리스너 컨테이너를 멈춥니다.
 *
 * <p>계약은 DLT 발행이 실패하면 원본 offset 을 성공 처리하지 않도록 요구합니다. 그
 * 규칙만 지키면 같은 레코드를 무한히 다시 소비하므로, 연속 실패가 한도를 넘으면
 * 컨테이너를 중지해 유한하게 만들고 운영자가 인지할 수 있게 합니다.
 *
 * <p>실패 판정은 {@link CountingDeadLetterRecoverer} 의 카운트로 합니다. 상위 클래스가
 * 복구 예외를 던지는지 삼키는지에 의존하지 않습니다.
 */
@Slf4j
public class DltFailureStoppingErrorHandler extends DefaultErrorHandler {

    private final CountingDeadLetterRecoverer recoverer;
    private final KafkaListenerStopTracker stopTracker;
    private final int maxConsecutiveFailures;
    private final CommonContainerStoppingErrorHandler containerStopper =
        new CommonContainerStoppingErrorHandler();

    public DltFailureStoppingErrorHandler(
        CountingDeadLetterRecoverer recoverer,
        KafkaListenerStopTracker stopTracker,
        BackOff backOff,
        int maxConsecutiveFailures
    ) {
        super(recoverer, backOff);
        this.recoverer = recoverer;
        this.stopTracker = stopTracker;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
    }

    @Override
    public boolean handleOne(
        Exception thrownException,
        ConsumerRecord<?, ?> record,
        Consumer<?, ?> consumer,
        MessageListenerContainer container
    ) {
        try {
            boolean handled = super.handleOne(thrownException, record, consumer, container);
            stopIfDeadLetterKeepsFailing(record, consumer, container);
            return handled;
        } catch (RuntimeException e) {
            stopIfDeadLetterKeepsFailing(record, consumer, container);
            throw e;
        }
    }

    @Override
    public void handleRemaining(
        Exception thrownException,
        List<ConsumerRecord<?, ?>> records,
        Consumer<?, ?> consumer,
        MessageListenerContainer container
    ) {
        try {
            super.handleRemaining(thrownException, records, consumer, container);
            if (!records.isEmpty()) {
                stopIfDeadLetterKeepsFailing(records.get(0), consumer, container);
            }
        } catch (RuntimeException e) {
            if (!records.isEmpty()) {
                stopIfDeadLetterKeepsFailing(records.get(0), consumer, container);
            }
            throw e;
        }
    }

    /**
     * 한도를 넘었으면 컨테이너를 멈춥니다.
     *
     * <p>중지 과정에서 생긴 예외는 밖으로 던지지 않습니다. 원래의 처리 실패를 덮으면
     * 진짜 원인이 로그에서 사라집니다.
     *
     * <p>중지 성공 여부는 예외가 아니라 컨테이너 상태로 판정합니다.
     * {@link CommonContainerStoppingErrorHandler} 는 중지를 지시한 뒤 항상 예외를 던지므로,
     * 예외 유무로 판정하면 성공한 중지까지 실패로 읽힙니다.
     */
    private void stopIfDeadLetterKeepsFailing(
        ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, MessageListenerContainer container) {
        int failures = recoverer.failureCount(record);
        if (failures < maxConsecutiveFailures) {
            return;
        }

        String reason = "DLT 발행이 " + failures + "회 연속 실패했습니다. record="
            + CountingDeadLetterRecoverer.recordKey(record);
        log.error("DLT 발행이 {}회 연속 실패해 리스너 컨테이너를 중지합니다. record={}",
            failures, CountingDeadLetterRecoverer.recordKey(record));

        // 중지 사유를 먼저 남깁니다. health 의 판정은 컨테이너의 실제 상태에서 읽고 이 기록은
        // 거기에 설명을 더하는 값이라, 중지가 끝나기 전에 남겨도 상태가 어긋나지 않습니다.
        stopTracker.recordDeadLetterStop(
            container.getGroupId(), container.getListenerId(), record.topic(), reason);

        try {
            // handleOne 이 아니라 handleRemaining 을 부릅니다.
            // CommonContainerStoppingErrorHandler 는 handleOne 을 재정의하지 않아서, 그쪽으로
            // 부르면 CommonErrorHandler 의 기본 구현이 오류 로그만 남기고 끝납니다. 컨테이너를
            // 실제로 멈추는 경로는 handleRemaining 입니다.
            containerStopper.handleRemaining(
                new KafkaException("DLT 발행이 반복 실패해 컨테이너를 중지했습니다."),
                List.of(record), consumer, container);
        } catch (RuntimeException signal) {
            // CommonContainerStoppingErrorHandler 는 중지를 지시한 뒤 반드시 예외를 던집니다.
            // 호출부에서 이 레코드의 처리를 끊으라는 신호이지 중지 실패가 아닙니다. 이것을
            // 실패로 읽으면 아래 판정과 정리가 영영 실행되지 않습니다.
            log.debug("컨테이너 중지 신호를 받았습니다.", signal);
        }

        if (container.isInExpectedState()) {
            // 중지가 반영되지 않았습니다. 카운트를 남겨 둡니다. 여기서 지우면 컨테이너가 계속
            // 돌면서 같은 레코드가 임계값까지 다시 쌓여야 재시도되고, 중지 실패가 반복되는
            // 동안 에스컬레이션 없이 같은 주기만 돕니다.
            log.error("리스너 컨테이너가 중지되지 않았습니다. 실패 카운트를 유지합니다.");
            return;
        }

        // 중지에 성공했으므로 더 추적할 필요가 없습니다. 맵이 남지 않게 정리합니다.
        recoverer.forget(record);
    }
}
