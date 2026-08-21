package com.mopl.global.event;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Kafka 리스너 컨테이너의 실행 상태를 health 로 노출합니다.
 *
 * <p>공통 오류 처리는 DLT 발행이 반복 실패하면 리스너 컨테이너를 멈춥니다. 원본 offset 을
 * 잘못 완료하지 않기 위한 동작이지만, 그 뒤로 소비는 조용히 멈춰 있습니다. 프로세스는 살아
 * 있고 REST 는 정상 응답하므로 다른 어떤 지표에도 흔적이 없습니다.
 *
 * <p>판정 기준은 {@code isInExpectedState()} 입니다. spring-kafka 는 오류 처리가 멈춘
 * 컨테이너를 비정상 중지로 표시하고, 운영자가 의도해서 멈춘 것이나 아직 기동하지 않은 것과
 * 구분해 줍니다. 단순히 {@code isRunning()} 이 거짓인지만 보면
 * {@code mopl.kafka.listener.auto-startup=false} 로 띄운 환경이 전부 실패로 잡힙니다.
 *
 * <p>이 component 는 liveness 와 readiness 어느 group 에도 넣지 않습니다.
 *
 * <ul>
 *   <li>liveness 에 넣으면 오케스트레이터가 프로세스를 재시작합니다. 그런데 DLT 가 아직
 *       복구되지 않았다면 다시 띄운 리스너가 같은 이유로 또 멈춥니다. 원인은 그대로인 채
 *       재시작만 반복됩니다.</li>
 *   <li>readiness 에 넣으면 로드밸런서가 인스턴스를 빼 버립니다. REST 요청은 정상 처리할 수
 *       있는 인스턴스인데 Kafka 문제로 처리 용량만 줄어듭니다.</li>
 * </ul>
 *
 * <p>대신 전체 {@code /actuator/health} 집계에는 반영되므로 상태가 조용히 {@code UP} 으로
 * 남지 않습니다. 사람이 개입해야 하는 상황이고, 사람이 개입할 때까지 드러나 있어야 합니다.
 */
@Component("kafkaListener")
public class KafkaListenerHealthIndicator implements HealthIndicator {

    private final KafkaListenerEndpointRegistry listenerEndpointRegistry;
    private final KafkaListenerStopTracker stopTracker;

    public KafkaListenerHealthIndicator(
        KafkaListenerEndpointRegistry listenerEndpointRegistry,
        KafkaListenerStopTracker stopTracker
    ) {
        this.listenerEndpointRegistry = listenerEndpointRegistry;
        this.stopTracker = stopTracker;
    }

    @Override
    public Health health() {
        Collection<MessageListenerContainer> containers =
            listenerEndpointRegistry.getListenerContainers();

        Map<String, Object> listeners = new LinkedHashMap<>();
        boolean stoppedAbnormally = false;

        for (MessageListenerContainer container : containers) {
            boolean abnormal = !container.isInExpectedState();
            stoppedAbnormally |= abnormal;
            listeners.put(listenerKey(container), describe(container, abnormal));
        }

        Health.Builder builder = stoppedAbnormally ? Health.down() : Health.up();
        return builder
            .withDetail("containers", containers.size())
            .withDetail("listeners", listeners)
            .build();
    }

    private Map<String, Object> describe(MessageListenerContainer container, boolean abnormal) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("groupId", container.getGroupId());
        detail.put("topics", topicsOf(container));
        detail.put("running", container.isRunning());
        detail.put("stoppedAbnormally", abnormal);

        // 중지 사유는 지금 멈춰 있는 컨테이너에만 붙입니다. 다시 띄운 뒤에도 기록은 남아
        // 있는데, 그것까지 보여주면 이미 해소된 원인을 현재 상태처럼 읽게 됩니다.
        if (abnormal) {
            stopTracker.lastStop(container.getGroupId()).ifPresent(stop -> {
                detail.put("stoppedAt", stop.stoppedAt().toString());
                detail.put("stoppedTopic", stop.topic());
                detail.put("reason", stop.reason());
            });
        }
        return detail;
    }

    /**
     * 컨테이너 식별자입니다.
     *
     * <p>{@code listenerId} 는 리스너를 등록할 때 이름을 주지 않으면 spring-kafka 가 만들어
     * 붙이므로 비어 있을 수 있습니다. 그때는 Consumer Group 으로 대신합니다.
     */
    private String listenerKey(MessageListenerContainer container) {
        String listenerId = container.getListenerId();
        return listenerId != null ? listenerId : String.valueOf(container.getGroupId());
    }

    private List<String> topicsOf(MessageListenerContainer container) {
        String[] topics = container.getContainerProperties().getTopics();
        return topics == null ? List.of() : Arrays.asList(topics);
    }
}
