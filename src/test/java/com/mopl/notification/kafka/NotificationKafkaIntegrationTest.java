package com.mopl.notification.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.event.MoplTopics;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import com.mopl.notification.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = {
    "mopl.kafka.topic.auto-create=true",
    "mopl.kafka.listener.auto-startup=true"
})
class NotificationKafkaIntegrationTest {

    private static final Duration TIMEOUT =
        Duration.ofSeconds(30);

    private static final UUID SENDER_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID RECEIVER_ID = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    private static final UUID DIRECT_MESSAGE_ID =
        UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

    private static final UUID EVENT_ID = UUID.fromString(
        "55555555-5555-5555-5555-555555555555"
    );

    @Container
    @ServiceConnection
    static KafkaContainer kafka =
        new KafkaContainer(
            DockerImageName.parse(
                "confluentinc/cp-kafka:7.6.1"
            )
        );

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(
            "postgres:16"
        );

    @Autowired
    KafkaTemplate<String, EventEnvelope>
        eventKafkaTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        insertUser(
            SENDER_ID,
            "sender@example.com",
            "발신자"
        );

        insertUser(
            RECEIVER_ID,
            "receiver@example.com",
            "수신자"
        );
    }

    @Test
    @DisplayName("Kafka DM 이벤트를 소비하면 알림을 DB에 저장")
    void consume_directMessageCreated_savesNotification()
        throws Exception {

        // given
        EventEnvelope envelope =
            new EventEnvelope(
                EVENT_ID,
                "direct-message.created",
                1,
                Instant.parse(
                    "2026-08-14T01:00:00Z"
                ),
                DIRECT_MESSAGE_ID,
                objectMapper.valueToTree(
                    Map.of(
                        "directMessageId",
                        DIRECT_MESSAGE_ID,
                        "conversationId",
                        CONVERSATION_ID,
                        "senderId",
                        SENDER_ID,
                        "receiverId",
                        RECEIVER_ID,
                        "contentPreview",
                        "안녕하세요"
                    )
                )
            );

        // when
        eventKafkaTemplate.send(
            MoplTopics.DIRECT_MESSAGE_EVENTS,
            DIRECT_MESSAGE_ID.toString(),
            envelope
        ).get();

        // then
        await()
            .atMost(TIMEOUT)
            .untilAsserted(() ->
                assertThat(
                    countNotification(
                        EVENT_ID,
                        RECEIVER_ID
                    )
                ).isEqualTo(1L)
            );

        Notification saved =
            notificationRepository.findAll()
                .stream()
                .filter(notification ->
                    EVENT_ID.equals(
                        notification.getSourceEventId()
                    )
                )
                .findFirst()
                .orElseThrow();

        assertThat(saved.getReceiverId())
            .isEqualTo(RECEIVER_ID);

        assertThat(saved.getSourceEventId())
            .isEqualTo(EVENT_ID);

        assertThat(saved.getType())
            .isEqualTo(
                NotificationType.DIRECT_MESSAGE
            );

        assertThat(saved.getResourceId())
            .isEqualTo(CONVERSATION_ID);

        assertThat(saved.getSourceEntityId())
            .isEqualTo(DIRECT_MESSAGE_ID);

        assertThat(saved.getTitle())
            .isEqualTo("[DM] 발신자");

        assertThat(saved.getContent())
            .isEqualTo("안녕하세요");

        assertThat(saved.getLevel())
            .isEqualTo(NotificationLevel.INFO);
    }

    private Long countNotification(
        UUID sourceEventId,
        UUID receiverId
    ) {
        return jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM notifications
            WHERE source_event_id = ?
                AND receiver_id = ?
            """,
            Long.class,
            sourceEventId,
            receiverId
        );
    }

    private void insertUser(
        UUID userId,
        String email,
        String name
    ) {
        Instant now =
            Instant.parse("2026-08-14T00:00:00Z");

        jdbcTemplate.update(
            """
            INSERT INTO users (
                id,
                created_at,
                updated_at,
                email,
                password_hash,
                name,
                role
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """,
            userId,
            Timestamp.from(now),
            Timestamp.from(now),
            email,
            "password-hash",
            name,
            "USER"
        );
    }
}
