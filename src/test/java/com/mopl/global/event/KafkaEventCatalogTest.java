package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.notification.kafka.payload.DirectMessageCreatedPayload;
import com.mopl.notification.kafka.payload.FollowCreatedPayload;
import com.mopl.notification.kafka.payload.PlaylistSubscriptionCreatedPayload;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaEventCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("이벤트 카탈로그의 type과 version 조합은 유일하다")
    void typeAndVersion_areUnique() {
        Set<String> keys = new HashSet<>();

        for (KafkaEventContract contract : KafkaEventContract.contracts()) {
            assertThat(keys.add(contract.type() + ":" + contract.version()))
                .as(contract.name())
                .isTrue();
            assertThat(MoplTopics.eventTopics()).contains(contract.topic());
        }
    }

    @Test
    @DisplayName("모든 카탈로그 이벤트는 최소 payload fixture와 라우팅 계약을 만족한다")
    void everyContract_hasValidFixture() throws Exception {
        for (KafkaEventContract contract : KafkaEventContract.contracts()) {
            JsonNode fixture = readFixture(contract);
            EventEnvelope envelope = objectMapper.treeToValue(
                fixture.required("envelope"), EventEnvelope.class);

            assertThat(envelope.type()).as(contract.name()).isEqualTo(contract.type());
            assertThat(envelope.version()).as(contract.name()).isEqualTo(contract.version());
            assertThat(fixture.required("expectedTopic").asText())
                .as(contract.name())
                .isEqualTo(contract.topic());
            assertThat(fixture.required("expectedPartitionKey").asText())
                .as(contract.name())
                .isEqualTo(contract.partitionKey(envelope));
            assertThat(fixture.required("expectedOrderingScope").asText())
                .as(contract.name())
                .isEqualTo(contract.orderingScope());

            assertThat(iterableFieldNames(envelope.payload()))
                .as(contract.name())
                .containsAll(contract.requiredPayloadFields());

            Object payload = objectMapper.treeToValue(
                envelope.payload(), payloadType(contract));
            JsonNode roundTrippedPayload = objectMapper.valueToTree(payload);
            assertThat(roundTrippedPayload)
                .as(contract.name())
                .isEqualTo(envelope.payload());
        }
    }

    private JsonNode readFixture(KafkaEventContract contract) throws Exception {
        String path = "/event-fixtures/" + contract.type() + ".v" + contract.version() + ".json";
        try (InputStream input = KafkaEventCatalogTest.class.getResourceAsStream(path)) {
            assertThat(input)
                .as("카탈로그 이벤트 fixture: " + path)
                .isNotNull();
            return objectMapper.readTree(input);
        }
    }

    private Class<?> payloadType(KafkaEventContract contract) {
        return switch (contract) {
            case FOLLOW_CREATED -> FollowCreatedPayload.class;
            case PLAYLIST_SUBSCRIPTION_CREATED -> PlaylistSubscriptionCreatedPayload.class;
            case DIRECT_MESSAGE_CREATED -> DirectMessageCreatedPayload.class;
        };
    }

    private Set<String> iterableFieldNames(JsonNode payload) {
        Set<String> fields = new HashSet<>();
        payload.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
}
