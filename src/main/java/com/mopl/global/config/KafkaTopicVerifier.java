package com.mopl.global.config;

import com.mopl.global.event.MoplTopics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.TimeoutException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.KafkaException;
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

        Map<String, TopicDescription> found = describeAll(expected);

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

    /**
     * 요청한 토픽 중 실제로 존재하는 것만 모아 돌려줍니다.
     *
     * <p>{@link KafkaAdmin#describeTopics(String...)} 은 하나라도 없으면 결과를 부분적으로
     * 돌려주지 않고 예외를 던집니다. 그대로 두면 무엇이 없는지 알려주지 못하고 브로커 접속
     * 실패와 구분되지도 않으므로, 한 번에 실패하면 하나씩 다시 확인해 없는 토픽을 가려냅니다.
     *
     * <p>정상 기동에서는 한 번의 호출로 끝납니다. 토픽별 재확인은 이미 기동이 실패로 끝나는
     * 경로에서만 일어납니다.
     *
     * <p>브로커에 접속하지 못한 경우에는 재확인하지 않습니다. 토픽마다 접속 한도를 다시
     * 기다리면 기동 실패가 몇 분 뒤에야 드러나고, 그렇게 얻는 목록도 "전부 없음"이라 접속
     * 실패라는 사실보다 덜 정확합니다.
     */
    private Map<String, TopicDescription> describeAll(List<String> topics) {
        try {
            return kafkaAdmin.describeTopics(topics.toArray(new String[0]));
        } catch (KafkaException e) {
            requireReachableBroker(e);
            log.warn("Kafka 토픽을 한 번에 확인하지 못해 하나씩 확인합니다.", e);
        }

        Map<String, TopicDescription> found = new LinkedHashMap<>();
        for (String topic : topics) {
            try {
                found.putAll(kafkaAdmin.describeTopics(topic));
            } catch (KafkaException e) {
                log.debug("Kafka 토픽을 확인하지 못했습니다. topic={}", topic, e);
            }
        }
        return found;
    }

    /**
     * 접속 자체가 안 된 경우를 없는 토픽과 구분합니다.
     *
     * <p>둘을 같은 메시지로 다루면 브로커 주소가 틀린 배포에서 토픽을 만들라는 안내를 보고
     * 엉뚱한 곳을 찾게 됩니다.
     */
    private void requireReachableBroker(KafkaException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                throw new IllegalStateException(
                    "Kafka 브로커에 접속하지 못했습니다. 주소와 연결 상태를 확인하세요.", e);
            }
        }
    }
}
