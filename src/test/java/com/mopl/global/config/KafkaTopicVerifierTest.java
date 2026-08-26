package com.mopl.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.event.MoplTopics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.TopicPartitionInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 기동 시 토픽 확인이 무엇이 없는지 알려주는지 검증합니다.
 *
 * <p>브로커 없이 확인합니다. 검증 대상은 Kafka 동작이 아니라, 토픽이 없을 때 어떤 메시지로
 * 기동을 멈추는가입니다.
 */
class KafkaTopicVerifierTest {

    private static final int EXPECTED_PARTITIONS = 3;

    private List<String> requiredTopics() {
        List<String> topics = new ArrayList<>();
        for (String topic : MoplTopics.eventTopics()) {
            topics.add(topic);
            topics.add(MoplTopics.deadLetterTopicOf(topic));
        }
        return topics;
    }

    private TopicDescription description(String name) {
        Node node = new Node(1, "broker", 9092);
        List<TopicPartitionInfo> partitions = new ArrayList<>();
        for (int partition = 0; partition < EXPECTED_PARTITIONS; partition++) {
            partitions.add(new TopicPartitionInfo(partition, node, List.of(node), List.of(node)));
        }
        return new TopicDescription(name, false, partitions);
    }

    @Test
    @DisplayName("필요한 토픽이 모두 있으면 기동을 막지 않는다")
    void passesWhenAllTopicsExist() {
        KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
        Map<String, TopicDescription> all = new LinkedHashMap<>();
        requiredTopics().forEach(topic -> all.put(topic, description(topic)));
        when(kafkaAdmin.describeTopics(any(String[].class))).thenReturn(all);

        assertThatCode(() -> new KafkaTopicVerifier(kafkaAdmin).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    /**
     * {@code describeTopics} 는 요청한 토픽이 하나라도 없으면 부분 결과 대신 예외를 던집니다.
     *
     * <p>그 예외를 그대로 흘리면 어떤 토픽이 없는지 알 수 없고 브로커 접속 실패와도 구분되지
     * 않습니다. 하나씩 다시 확인해 없는 토픽만 메시지에 담아야 합니다.
     */
    @Test
    @DisplayName("없는 토픽이 있으면 그 이름을 메시지에 담아 기동을 멈춘다")
    void failsWithMissingTopicNames() {
        String missing = MoplTopics.deadLetterTopicOf(MoplTopics.FOLLOW_EVENTS);

        KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
        when(kafkaAdmin.describeTopics(any(String[].class)))
            .thenAnswer(invocation -> {
                // 가변 인자는 Mockito 가 개별 인자로 펼쳐서 넘깁니다.
                Object[] topics = invocation.getArguments();
                if (topics.length > 1 || missing.equals(topics[0])) {
                    throw new KafkaException("Failed to obtain topic descriptions");
                }
                String topic = (String) topics[0];
                return Map.of(topic, description(topic));
            });

        assertThatThrownBy(() -> new KafkaTopicVerifier(kafkaAdmin).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(missing);
    }

    /**
     * 접속 실패를 없는 토픽과 같은 메시지로 다루면, 브로커 주소가 틀린 배포에서 토픽을
     * 만들라는 안내를 보고 엉뚱한 곳을 찾게 됩니다.
     *
     * <p>토픽별 재확인도 하지 않아야 합니다. 토픽마다 접속 한도를 다시 기다리면 기동 실패가
     * 몇 분 뒤에야 드러납니다.
     */
    @Test
    @DisplayName("브로커에 접속하지 못하면 접속 실패로 알리고 토픽별로 다시 묻지 않는다")
    void failsFastWhenBrokerUnreachable() {
        KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
        when(kafkaAdmin.describeTopics(any(String[].class)))
            .thenThrow(new KafkaException("Failed to obtain topic descriptions",
                new TimeoutException("Timed out waiting for a node assignment.")));

        assertThatThrownBy(() -> new KafkaTopicVerifier(kafkaAdmin).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("접속하지 못했습니다");

        verify(kafkaAdmin, times(1)).describeTopics(any(String[].class));
    }
}
