package com.mopl.directmessage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.ParticipantSlot;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.ConversationRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.global.event.EventEnvelope;
import com.mopl.global.outbox.OutboxEvent;
import com.mopl.global.outbox.OutboxEventRepository;
import com.mopl.global.outbox.OutboxRecorder;
import com.mopl.global.outbox.OutboxRecorderImpl;
import com.mopl.global.outbox.OutboxStatus;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(
    DirectMessageOutboxIntegrationTest
        .OutboxTestConfig.class
)
class DirectMessageOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(
            "postgres:16"
        );

    @Autowired
    DirectMessageService directMessageService;

    @Autowired
    DirectMessageRepository directMessageRepository;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    ConversationParticipantRepository
        participantRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ControllableOutboxRecorder outboxRecorder;

    private UUID senderId;
    private UUID receiverId;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        outboxRecorder.reset();

        outboxEventRepository.deleteAll();
        directMessageRepository.deleteAll();
        participantRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();

        User sender =
            userRepository.save(
                createUser(
                    "sender@test.com",
                    "발신자"
                )
            );

        User receiver =
            userRepository.save(
                createUser(
                    "receiver@test.com",
                    "수신자"
                )
            );

        senderId = sender.getId();
        receiverId = receiver.getId();

        Conversation conversation =
            conversationRepository.save(
                Conversation.create()
            );

        conversationId =
            conversation.getId();

        participantRepository.saveAll(
            List.of(
                ConversationParticipant.create(
                    conversationId,
                    senderId,
                    ParticipantSlot.FIRST
                ),
                ConversationParticipant.create(
                    conversationId,
                    receiverId,
                    ParticipantSlot.SECOND
                )
            )
        );
    }

    @Test
    @DisplayName("DM과 Outbox 이벤트를 같은 트랜잭션으로 저장")
    void create_success_savesDirectMessageAndOutbox() throws Exception {
        // when
        DirectMessageDto result =
            directMessageService.create(
                senderId,
                conversationId,
                "  안녕하세요\n\n반갑습니다  "
            );

        // then
        assertThat(
            directMessageRepository.findById(
                result.id()
            )
        ).isPresent();

        List<OutboxEvent> outboxEvents =
            outboxEventRepository.findAll();

        assertThat(outboxEvents)
            .hasSize(1);

        OutboxEvent outboxEvent =
            outboxEvents.get(0);

        assertThat(outboxEvent.getType())
            .isEqualTo(
                "direct-message.created"
            );

        assertThat(outboxEvent.getVersion())
            .isEqualTo(1);

        assertThat(outboxEvent.getAggregateId())
            .isEqualTo(result.id());

        assertThat(outboxEvent.getPartitionKey())
            .isEqualTo(
                conversationId.toString()
            );

        assertThat(outboxEvent.getOrderingScope())
            .isEqualTo(
                "conversationId"
            );

        assertThat(outboxEvent.getDeduplicationKey())
            .isEqualTo(
                "direct-message.created:"
                    + result.id()
            );

        assertThat(outboxEvent.getStatus())
            .isEqualTo(
                OutboxStatus.PENDING
            );

        JsonNode payload =
            objectMapper.readTree(
                outboxEvent.getPayload()
            );

        assertThat(
            payload.get("directMessageId")
                .asText()
        ).isEqualTo(
            result.id().toString()
        );

        assertThat(
            payload.get("conversationId")
                .asText()
        ).isEqualTo(
            conversationId.toString()
        );

        assertThat(
            payload.get("senderId")
                .asText()
        ).isEqualTo(
            senderId.toString()
        );

        assertThat(
            payload.get("receiverId")
                .asText()
        ).isEqualTo(
            receiverId.toString()
        );

        assertThat(
            payload.get("contentPreview")
                .asText()
        ).isEqualTo(
            "안녕하세요 반갑습니다"
        );
    }

    @Test
    @DisplayName("Outbox 기록에 실패하면 DM 저장도 롤백")
    void create_outboxRecordFails_rollsBackDirectMessage() {
        // given
        outboxRecorder.failNextRecord();

        // when & then
        assertThatThrownBy(() ->
            directMessageService.create(
                senderId,
                conversationId,
                "롤백할 메시지"
            )
        )
            .isInstanceOf(
                DataIntegrityViolationException.class
            );

        assertThat(
            directMessageRepository.count()
        ).isZero();

        assertThat(
            outboxEventRepository.count()
        ).isZero();
    }

    private User createUser(
        String email,
        String name
    ) {
        return User.builder()
            .email(email)
            .passwordHash("password-hash")
            .name(name)
            .role(UserRole.USER)
            .locked(false)
            .build();
    }

    @TestConfiguration
    static class OutboxTestConfig {

        @Bean
        @Primary
        ControllableOutboxRecorder
            controllableOutboxRecorder(
                OutboxRecorderImpl delegate
            ) {
            return new ControllableOutboxRecorder(
                delegate
            );
        }
    }

    static class ControllableOutboxRecorder
        implements OutboxRecorder {

        private final OutboxRecorder delegate;

        private final AtomicBoolean failNext =
            new AtomicBoolean();

        ControllableOutboxRecorder(
            OutboxRecorder delegate
        ) {
            this.delegate = delegate;
        }

        void failNextRecord() {
            failNext.set(true);
        }

        void reset() {
            failNext.set(false);
        }

        @Override
        public void record(
            EventEnvelope envelope,
            String partitionKey,
            String orderingScope,
            String deduplicationKey
        ) {
            if (failNext.compareAndSet(
                true,
                false
            )) {
                throw new DataIntegrityViolationException(
                    "Outbox 기록 실패"
                );
            }

            delegate.record(
                envelope,
                partitionKey,
                orderingScope,
                deduplicationKey
            );
        }
    }
}
