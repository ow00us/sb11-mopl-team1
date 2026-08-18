package com.mopl.global.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox relay 를 주기적으로 실행합니다.
 *
 * <p>주기 실행을 {@link OutboxRelay} 에서 분리합니다. 같은 클래스에 두면 relay 를 직접
 * 만들어 호출하는 테스트에서도 스케줄이 함께 붙어, 준비 중인 데이터를 주기 실행이 먼저
 * 가져갑니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mopl.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelay outboxRelay;

    public OutboxRelayScheduler(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    @Scheduled(fixedDelayString = "${mopl.outbox.relay.interval}")
    public void relay() {
        try {
            outboxRelay.publishClaimed();
        } catch (RuntimeException e) {
            // 예외가 빠져나가면 스케줄러가 이 작업의 이후 실행을 등록하지 않습니다.
            log.error("Outbox relay 주기 실행이 실패했습니다.", e);
        }
    }
}
