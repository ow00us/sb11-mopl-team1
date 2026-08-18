package com.mopl.global.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 구독이 붙지 않은 상태를 주기적으로 확인해 다시 시작합니다.
 *
 * <p>기동 시점에 Redis 가 준비되지 않았을 수 있습니다. 한 번 실패하고 끝나면 그 인스턴스는
 * 재배포 전까지 다른 인스턴스의 메시지를 영영 받지 못합니다.
 *
 * <p>이미 붙은 구독이 끊긴 경우의 재연결은 컨테이너가 자체적으로 처리합니다. 여기서 다루는
 * 것은 처음부터 붙지 못한 경우입니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mopl.realtime.relay.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeRelaySubscriptionStarter {

    private final RealtimeRelayListenerContainer container;

    public RealtimeRelaySubscriptionStarter(RealtimeRelayListenerContainer container) {
        this.container = container;
    }

    @Scheduled(fixedDelayString = "${mopl.realtime.relay.subscribe-retry-interval}")
    public void ensureSubscribed() {
        // 실행 중 여부만으로는 부족합니다. 구독이 붙지 않은 채 실행 중으로 남을 수 있어
        // 실제로 듣고 있는지까지 봅니다.
        if (container.isRunning() && container.isListening()) {
            return;
        }

        log.info("실시간 중계 구독을 다시 시작합니다.");
        container.stop();
        container.start();
    }
}
