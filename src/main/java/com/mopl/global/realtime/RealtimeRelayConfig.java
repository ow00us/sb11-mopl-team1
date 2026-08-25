package com.mopl.global.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;

/**
 * 실시간 중계 구독을 구성합니다.
 *
 * <p>{@code mopl.realtime.relay.enabled} 로 끌 수 있습니다. Redis 없이 다른 기능을 개발하거나
 * 테스트할 때 구독 컨테이너가 재연결을 반복하지 않도록 하기 위한 스위치입니다.
 *
 * <p>구독 시작 실패는 기동을 막지 않습니다. 실시간 중계는 부가 경로이므로, Redis 가 준비되지
 * 않았다고 REST 와 도메인 기능까지 함께 세울 이유가 없습니다.
 */
@Configuration
@ConditionalOnProperty(name = "mopl.realtime.relay.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeRelayConfig {

    /**
     * 목적지 handler 를 주입받아 구독자를 만듭니다.
     *
     * <p>handler 가 하나도 없어도 구독은 둡니다. 도메인 연결은 후속 작업이고, 그때까지 받은
     * 메시지는 처리 대상 없이 지나갑니다.
     */
    @Bean
    public RealtimeRelaySubscriber realtimeRelaySubscriber(
        ObjectMapper objectMapper,
        RealtimeInstanceId instanceId,
        List<RealtimeMessageHandler> handlers,
        RealtimeRelayMetrics metrics
    ) {
        return new RealtimeRelaySubscriber(objectMapper, instanceId, handlers, metrics);
    }

    @Bean
    public RealtimeRelayListenerContainer realtimeRelayListenerContainer(
        RedisConnectionFactory redisConnectionFactory,
        RealtimeRelaySubscriber realtimeRelaySubscriber
    ) {
        RealtimeRelayListenerContainer container = new RealtimeRelayListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(
            realtimeRelaySubscriber, new ChannelTopic(RealtimeChannels.MESSAGES));
        return container;
    }
}
