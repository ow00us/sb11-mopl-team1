package com.mopl.global.config;

import com.mopl.global.event.MoplTopics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.TopicDescription;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * 토픽을 만들지 않는 환경에서 필요한 토픽이 실제로 있는지 기동 시 확인합니다.
 *
 * <p>운영은 {@code mopl.kafka.topic.auto-create} 를 false 로 두므로 애플리케이션이
 * 토픽을 만들지 않습니다. 그러면 토픽이나 DLT 가 없어도 기동은 성공하고, 발행이나
 * 복구 시점에야 실패합니다. 특히 DLT 가 없으면 DLT 발행이 실패하면서 원본 처리까지
 * 막히므로 기동 시 드러내는 편이 낫습니다.
 *
 * <p>검사는 {@code mopl.kafka.topic.verify} 가 true 일 때만 합니다. 브로커가 없는
 * 로컬 개발과 Kafka 를 쓰지 않는 테스트에서 기동이 실패하지 않도록 기본값은 false 입니다.
 *
 * <p>파티션 수가 기대와 다르면 실패시키지 않고 경고만 남깁니다. 운영에서 파티션을 늘리는
 * 것은 정상적인 운영 행위이고, 그것 때문에 배포가 막히면 손해가 더 큽니다. 없는 토픽만
 * 기동 실패로 다룹니다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mopl.kafka.topic.verify", havingValue = "true")
public class KafkaTopicVerifier implements InitializingBean {

    private static final int EXPECTED_PARTITIONS = 3;

    private final KafkaAdmin kafkaAdmin;

    public KafkaTopicVerifier(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> expected = new ArrayList<>();
        for (String topic : MoplTopics.eventTopics()) {
            expected.add(topic);
            expected.add(MoplTopics.deadLetterTopicOf(topic));
        }

        Map<String, TopicDescription> found = kafkaAdmin.describeTopics(expected.toArray(new String[0]));

        List<String> missing = expected.stream().filter(topic -> !found.containsKey(topic)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "필요한 Kafka 토픽이 없습니다. 토픽을 준비한 뒤 다시 기동하세요: " + missing);
        }

        found.forEach((topic, description) -> {
            int partitions = description.partitions().size();
            if (partitions != EXPECTED_PARTITIONS) {
                log.warn("Kafka 토픽 파티션 수가 기대와 다릅니다. topic={}, 기대={}, 실제={}",
                    topic, EXPECTED_PARTITIONS, partitions);
            }
        });

        log.info("Kafka 토픽 {}개를 확인했습니다.", expected.size());
    }
}
