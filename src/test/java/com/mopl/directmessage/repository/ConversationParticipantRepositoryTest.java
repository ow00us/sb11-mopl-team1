package com.mopl.directmessage.repository;

import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.global.config.JpaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
public class ConversationParticipantRepositoryTest {

    private static final UUID USER_ID_1 =
        UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID USER_ID_2 =
        UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    ConversationParticipantRepository participantRepository;

    @Autowired
    TestEntityManager entityManager;

    private void insertUser(
        UUID userId,
        String email,
        String name
    ) {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");

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

    @BeforeEach
    void setUp() {

        insertUser(
            USER_ID_1,
            "receiver@example.com",
            "receiver"
        );

        insertUser(
            USER_ID_2,
            "other@example.com",
            "other"
        );
    }

    @Test
    @DisplayName("한 대화에 저장된 두 참여자를 조회")
    void saveAndFindParticipants() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(Conversation.create());

        ConversationParticipant participant1 =
            ConversationParticipant.create(conversation.getId(), USER_ID_1);

        ConversationParticipant participant2 =
            ConversationParticipant.create(conversation.getId(), USER_ID_2);

        participantRepository.saveAllAndFlush(
            List.of(participant1, participant2)
        );
        entityManager.clear();

        // when
        List<ConversationParticipant> participants =
            participantRepository.findAllByConversationId(conversation.getId());

        // then
        assertThat(participants).hasSize(2);
        assertThat(participants)
            .extracting(ConversationParticipant::getUserId)
            .containsExactlyInAnyOrder(USER_ID_1, USER_ID_2);
    }

}
