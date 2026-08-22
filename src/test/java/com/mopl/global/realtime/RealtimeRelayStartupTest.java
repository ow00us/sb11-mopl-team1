package com.mopl.global.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Redis 가 없는 상태에서도 애플리케이션이 기동하는지 확인합니다.
 *
 * <p>구독 컨테이너는 생명주기 빈이라 시작에 실패하면 컨텍스트 전체가 뜨지 않습니다. 실시간
 * 중계는 부가 경로인데 그 때문에 REST 와 도메인 기능까지 함께 세우면 안 됩니다.
 */
@SpringBootTest(classes = {
    RealtimeRelayConfig.class,
    RealtimeRelayPublisher.class,
    RealtimeInstanceId.class,
    RealtimeRelayMetrics.class,
    RealtimeRelayStateMetrics.class,
    SimpleMeterRegistry.class,
    JacksonAutoConfiguration.class,
    RedisAutoConfiguration.class
})
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "mopl.realtime.relay.enabled=true",
    // 아무도 듣고 있지 않은 포트입니다.
    "spring.data.redis.host=127.0.0.1",
    "spring.data.redis.port=1",
    "spring.data.redis.timeout=1s",
    "spring.data.redis.connect-timeout=1s"
})
class RealtimeRelayStartupTest {

    @Autowired
    RealtimeRelayListenerContainer container;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    @DisplayName("Redis에 연결하지 못해도 컨텍스트가 기동한다")
    void contextStartsWithoutRedis() {
        assertThat(container).isNotNull();
    }

    /**
     * 구독을 시작하지 못한 상태가 실행 중으로 남으면, 다시 시도하는 쪽이 조건을 만족하지
     * 못해 그 인스턴스는 재배포 전까지 다른 인스턴스의 메시지를 받지 못합니다.
     */
    @Test
    @DisplayName("구독을 시작하지 못하면 실행 중이 아닌 상태로 남는다")
    void failedSubscriptionIsNotRunning() {
        assertThat(container.isRunning()).isFalse();
        assertThat(container.isSubscribed()).isFalse();
    }

    /**
     * 구독하지 못한 채 기동한 인스턴스는 다른 인스턴스의 메시지를 받지 못합니다. 기동에
     * 성공했다는 이유로 그 사실이 가려지면 안 됩니다.
     */
    @Test
    @DisplayName("구독하지 못한 상태를 지표가 0으로 드러낸다")
    void failedSubscriptionIsVisibleInMetrics() {
        assertThat(meterRegistry.get("mopl.realtime.relay.subscribed").gauge().value())
            .isZero();
    }
}
