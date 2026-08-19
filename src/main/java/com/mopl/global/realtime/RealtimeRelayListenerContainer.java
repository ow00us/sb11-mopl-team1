package com.mopl.global.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 구독 시작 실패로 애플리케이션 기동이 막히지 않게 합니다.
 *
 * <p>{@link RedisMessageListenerContainer} 는 생명주기 빈이라 시작에 실패하면 컨텍스트가
 * 뜨지 않습니다. 그러면 Redis 가 준비되지 않은 상태에서 애플리케이션 전체가 기동하지
 * 못합니다. 실시간 중계는 부가 경로이므로 REST 와 도메인 기능까지 함께 세울 이유가 없습니다.
 *
 * <p>실패는 로그로 남기고 {@link RealtimeRelaySubscriptionStarter} 가 다시 시도합니다.
 *
 * <p>실패한 시작은 되돌립니다. 상위 클래스는 구독에 실패해도 실행 중 상태로 남기는데, 그대로
 * 두면 다시 시도하는 쪽이 이미 돌고 있다고 보고 아무것도 하지 않습니다.
 */
@Slf4j
public class RealtimeRelayListenerContainer extends RedisMessageListenerContainer {

    @Override
    public void start() {
        try {
            super.start();
        } catch (RuntimeException e) {
            log.warn("실시간 중계 구독을 시작하지 못했습니다. 다시 시도합니다.", e);
            rollbackFailedStart();
        }
    }

    private void rollbackFailedStart() {
        try {
            super.stop();
        } catch (RuntimeException e) {
            log.warn("실시간 중계 구독을 정리하지 못했습니다.", e);
        }
    }
}
