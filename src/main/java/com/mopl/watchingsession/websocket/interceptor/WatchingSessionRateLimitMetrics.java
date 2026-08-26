package com.mopl.watchingsession.websocket.interceptor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * STOMP 유량·구독 개수 제한에 걸려 무음으로 드롭된 프레임 수를 목적지 종류별로 노출한다.
 * 무음 드롭은 운영 로그만으로는 발생 여부를 알기 어려우므로 지표가 유일한 관측 수단이다.
 */
@Component
public class WatchingSessionRateLimitMetrics {

    private final Counter heartbeatDropped;
    private final Counter chatSendDropped;
    private final Counter watchSubscribeDropped;
    private final Counter chatSubscribeDropped;

    public WatchingSessionRateLimitMetrics(MeterRegistry meterRegistry) {
        this.heartbeatDropped = counter(meterRegistry, "heartbeat-send");
        this.chatSendDropped = counter(meterRegistry, "chat-send");
        this.watchSubscribeDropped = counter(meterRegistry, "watch-subscribe");
        this.chatSubscribeDropped = counter(meterRegistry, "chat-subscribe");
    }

    private Counter counter(MeterRegistry registry, String destination) {
        return Counter.builder("mopl.watchingsession.ratelimit.dropped")
            .tag("destination", destination)
            .description("유량·구독 개수 제한에 걸려 무음으로 드롭된 STOMP 프레임 수")
            .register(registry);
    }

    public void recordHeartbeatDropped() {
        heartbeatDropped.increment();
    }

    public void recordChatSendDropped() {
        chatSendDropped.increment();
    }

    public void recordWatchSubscribeDropped() {
        watchSubscribeDropped.increment();
    }

    public void recordChatSubscribeDropped() {
        chatSubscribeDropped.increment();
    }
}
