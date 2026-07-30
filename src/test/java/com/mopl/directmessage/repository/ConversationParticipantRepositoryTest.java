package com.mopl.directmessage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.ParticipantSlot;
import com.mopl.global.config.JpaConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            );

        ConversationParticipant participant2 =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_2,
                ParticipantSlot.SECOND
            );

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

        assertThat(participants)
            .extracting(ConversationParticipant::getParticipantSlot)
            .containsExactlyInAnyOrder(
                ParticipantSlot.FIRST,
                ParticipantSlot.SECOND
            );
    }

    @Test
    @DisplayName("같은 대화에서 동일한 슬롯을 중복으로 사용할 수 없음")
    void saveParticipantsWithSameSlotFails() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(Conversation.create());

        ConversationParticipant participant1 =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            );

        ConversationParticipant participant2 =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_2,
                ParticipantSlot.FIRST
            );

        participantRepository.saveAndFlush(participant1);

        // when & then
        assertThatThrownBy(
            () -> participantRepository.saveAndFlush(participant2)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 사용자가 한 대화의 두 슬롯을 차지할 수 없음")
    void saveSameUserInBothSlotsFails() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(Conversation.create());

        ConversationParticipant participant1 =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            );

        ConversationParticipant participant2 =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.SECOND
            );

        participantRepository.saveAndFlush(participant1);

        // when & then
        assertThatThrownBy(
            () -> participantRepository.saveAndFlush(participant2)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("한 대화에 세 번째 참여자를 저장할 수 없음")
    void saveThirdParticipantFails() {
        // given
        UUID userId3 =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

        insertUser(
            userId3,
            "third@example.com",
            "third"
        );

        Conversation conversation =
            conversationRepository.saveAndFlush(Conversation.create());

        ConversationParticipant participant1 =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            );

        ConversationParticipant participant2 =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_2,
                ParticipantSlot.SECOND
            );

        ConversationParticipant participant3 =
            ConversationParticipant.create(
                conversation.getId(),
                userId3,
                ParticipantSlot.FIRST
            );

        participantRepository.saveAllAndFlush(
            List.of(participant1, participant2)
        );

        // when & then
        assertThatThrownBy(
            () -> participantRepository.saveAndFlush(participant3)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("서로 다른 대화에서는 같은 슬롯을 사용할 수 있음")
    void saveSameSlotInDifferentConversations() {
        // given
        Conversation conversation1 =
            conversationRepository.saveAndFlush(Conversation.create());

        Conversation conversation2 =
            conversationRepository.saveAndFlush(Conversation.create());

        ConversationParticipant participant1 =
            ConversationParticipant.create(
                conversation1.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            );

        ConversationParticipant participant2 =
            ConversationParticipant.create(
                conversation2.getId(),
                USER_ID_2,
                ParticipantSlot.FIRST
            );

        // when
        List<ConversationParticipant> saved =
            participantRepository.saveAllAndFlush(
                List.of(participant1, participant2)
            );

        // then
        assertThat(saved).hasSize(2);
        assertThat(saved)
            .extracting(ConversationParticipant::getParticipantSlot)
            .containsOnly(ParticipantSlot.FIRST);
    }

}
