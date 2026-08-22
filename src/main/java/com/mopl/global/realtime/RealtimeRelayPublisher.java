package com.mopl.global.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 실시간 메시지를 다른 인스턴스로 내보냅니다.
 *
 * <p>자기 인스턴스에 연결된 대상에게 보내는 일은 여기서 하지 않습니다. 도메인이 이미 자기
 * 연결로 보낸 뒤, 다른 인스턴스에도 닿아야 할 때 이 발행을 함께 호출합니다. 여기서 자기
 * 인스턴스까지 전달하면 같은 사용자에게 두 번 갑니다.
 *
 * <p>발행 실패를 호출부로 던지지 않습니다. 호출부는 대개 REST 요청의 트랜잭션 안이고, Redis
 * 연결이 끊겼다는 이유로 이미 성공한 도메인 변경이 롤백되면 안 됩니다. 실시간 전달은
 * 부가 경로이고, 놓친 메시지는 도메인의 조회나 복구 경로가 메웁니다.
 *
 * <p>값은 타입 정보 없이 JSON 문자열로 보냅니다. 채널을 지나는 형식이 곧 계약이므로,
 * 자바 클래스 이름이 메시지에 실리면 클래스를 옮기는 것만으로 인스턴스 간 호환이 깨집니다.
 */
@Slf4j
@Component
public class RealtimeRelayPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final RealtimeInstanceId instanceId;
    private final RealtimeRelayMetrics metrics;

    public RealtimeRelayPublisher(
        StringRedisTemplate stringRedisTemplate,
        ObjectMapper objectMapper,
        RealtimeInstanceId instanceId,
        RealtimeRelayMetrics metrics
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.instanceId = instanceId;
        this.metrics = metrics;
    }

    /**
     * 다른 인스턴스로 실시간 메시지를 보냅니다.
     *
     * @param eventType   메시지 종류. 수신 측 handler 가 이 값으로 처리 대상을 고릅니다.
     * @param destination 전달 목적지. 표기 규칙은 도메인이 정합니다.
     * @param payload     목적지 도메인이 해석할 본문
     * @return 발행했으면 {@code true}, 실패해 건너뛰었으면 {@code false}
     */
    public boolean publish(String eventType, String destination, Object payload) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType 은 비어 있을 수 없습니다.");
        }
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination 은 비어 있을 수 없습니다.");
        }

        RealtimeMessage message = new RealtimeMessage(
            UUID.randomUUID(),
            instanceId.value(),
            eventType,
            destination,
            objectMapper.valueToTree(payload));

        try {
            stringRedisTemplate.convertAndSend(
                RealtimeChannels.MESSAGES, objectMapper.writeValueAsString(message));
            metrics.recordPublishSucceeded();
            return true;
        } catch (Exception e) {
            metrics.recordPublishFailed();
            log.warn("실시간 메시지 중계 발행에 실패했습니다. eventType={}, destination={}",
                eventType, destination, e);
            return false;
        }
    }
}
