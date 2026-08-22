package com.mopl.global.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

/**
 * 다른 인스턴스가 보낸 실시간 메시지를 받아 목적지 handler 로 넘깁니다.
 *
 * <p>버려야 할 메시지가 두 종류 있습니다. 자기가 발행한 메시지는 이미 자기 연결로 보냈으므로
 * 다시 전달하면 두 번 갑니다. 같은 messageId 가 다시 온 것도 마찬가지입니다.
 *
 * <p>한 메시지의 실패가 다음 메시지를 막지 않게 합니다. 형식이 깨진 메시지, 계약에 맞지 않는
 * 메시지, handler 하나의 예외가 구독 자체를 멈추면 그 인스턴스는 이후 모든 실시간 전달을
 * 잃습니다.
 */
@Slf4j
public class RealtimeRelaySubscriber implements MessageListener {

    /** 중복 판정에 기억할 메시지 수입니다. 실시간 전달에서 이보다 늦게 오는 중복은 의미가 없습니다. */
    private static final int RECENT_MESSAGE_CAPACITY = 10_000;

    private final ObjectMapper objectMapper;
    private final RealtimeInstanceId instanceId;
    private final List<RealtimeMessageHandler> handlers;
    private final RecentMessageIds recentMessageIds;
    private final RealtimeRelayMetrics metrics;

    public RealtimeRelaySubscriber(
        ObjectMapper objectMapper,
        RealtimeInstanceId instanceId,
        List<RealtimeMessageHandler> handlers,
        RealtimeRelayMetrics metrics
    ) {
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.handlers = handlers;
        this.metrics = metrics;
        this.recentMessageIds = new RecentMessageIds(RECENT_MESSAGE_CAPACITY);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        RealtimeMessage received = read(message);
        if (received == null) {
            return;
        }

        if (received.messageId() == null || received.originInstanceId() == null
            || received.eventType() == null || received.destination() == null) {
            metrics.recordDiscarded(RealtimeRelayDiscardReason.INCOMPLETE);
            log.warn("실시간 중계 메시지에 필수 값이 없습니다. messageId={}, eventType={}",
                received.messageId(), received.eventType());
            return;
        }

        if (instanceId.value().equals(received.originInstanceId())) {
            metrics.recordDiscarded(RealtimeRelayDiscardReason.SELF);
            log.trace("자기 인스턴스가 발행한 메시지를 건너뜁니다. messageId={}", received.messageId());
            return;
        }

        if (!recentMessageIds.markSeen(received.messageId())) {
            metrics.recordDiscarded(RealtimeRelayDiscardReason.DUPLICATE);
            log.debug("이미 전달한 실시간 메시지를 건너뜁니다. messageId={}", received.messageId());
            return;
        }

        dispatch(received);
    }

    private RealtimeMessage read(Message message) {
        try {
            return objectMapper.readValue(
                new String(message.getBody(), StandardCharsets.UTF_8), RealtimeMessage.class);
        } catch (Exception e) {
            // 본문을 로그에 남기지 않습니다. 실시간 메시지에는 사용자에게 보내는 내용이 들어
            // 있습니다.
            metrics.recordDiscarded(RealtimeRelayDiscardReason.MALFORMED);
            log.warn("실시간 중계 메시지를 읽지 못했습니다. 이 메시지는 버립니다.", e);
            return null;
        }
    }

    private void dispatch(RealtimeMessage received) {
        metrics.recordDelivered();

        for (RealtimeMessageHandler handler : handlers) {
            try {
                if (handler.supports(received.eventType())) {
                    handler.handle(received);
                }
            } catch (Exception e) {
                metrics.recordHandlerFailure(handler.getClass().getSimpleName());
                log.error("실시간 메시지 전달에 실패했습니다. handler={}, eventType={}, destination={}",
                    handler.getClass().getName(), received.eventType(), received.destination(), e);
            }
        }
    }
}
