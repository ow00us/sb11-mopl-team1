package com.mopl.global.config;

import com.mopl.global.event.MoplTopics;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * 공통 토픽을 선언적으로 생성합니다.
 *
 * <p>{@code mopl.kafka.topic.auto-create} 가 true 일 때만 동작합니다. 운영 토픽을
 * 애플리케이션 기동이 만들면 파티션 수 같은 결정이 코드 배포에 묶이므로, 운영에서는
 * 이 값을 false 로 두고 토픽을 미리 준비합니다.
 */
@Configuration
@ConditionalOnProperty(name = "mopl.kafka.topic.auto-create", havingValue = "true")
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    /**
     * 도메인 이벤트 토픽과 대응하는 DLT 를 함께 선언합니다.
     *
     * <p>DLT 를 broker 의 자동 생성에 맡기지 않습니다. 자동 생성은 broker 설정에 따라
     * 꺼져 있을 수 있고, 그러면 DLT 발행이 조용히 실패합니다.
     */
    @Bean
    public KafkaAdmin.NewTopics moplEventTopics() {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : MoplTopics.eventTopics()) {
            topics.add(newTopic(topic));
            topics.add(newTopic(MoplTopics.deadLetterTopicOf(topic)));
        }
        return new KafkaAdmin.NewTopics(topics.toArray(new NewTopic[0]));
    }

    private NewTopic newTopic(String name) {
        return TopicBuilder.name(name)
            .partitions(PARTITIONS)
            .replicas(REPLICAS)
            .build();
    }
}
