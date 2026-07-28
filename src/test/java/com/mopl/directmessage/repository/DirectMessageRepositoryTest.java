package com.mopl.directmessage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.DirectMessage;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
public class DirectMessageRepositoryTest {

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

    @Autowired
    DirectMessageRepository directMessageRepository;

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

    //readAt 초기값 확인
    @Test
    @DisplayName("DM 저장 후 ID로 조회")
    void saveAndFindDirectMessage() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(Conversation.create());

        ConversationParticipant participant =
            ConversationParticipant.create(conversation.getId(), USER_ID_1);

        participantRepository.saveAndFlush(participant);

        DirectMessage message = DirectMessage.create(
            conversation.getId(),
            USER_ID_1,
            "안녕하세요"
        );

        // when
        DirectMessage saved = directMessageRepository.saveAndFlush(message);
        entityManager.clear();

        DirectMessage found = directMessageRepository.findById(saved.getId())
            .orElseThrow();

        // then
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getConversationId()).isEqualTo(conversation.getId());
        assertThat(found.getSenderId()).isEqualTo(USER_ID_1);
        assertThat(found.getContent()).isEqualTo("안녕하세요");
        assertThat(found.getReadAt()).isNull();
    }

    @Test
    @DisplayName("대화 참여자가 아닌 사용자는 DM을 저장할 수 없음")
    void saveDirectMessageByNonParticipantFails() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(Conversation.create());

        ConversationParticipant participant =
            ConversationParticipant.create(conversation.getId(), USER_ID_1);

        participantRepository.saveAndFlush(participant);

        DirectMessage message = DirectMessage.create(
            conversation.getId(),
            USER_ID_2,
            "참여자가 아닌 사용자의 메시지"
        );

        // when & then
        assertThatThrownBy(
            () -> directMessageRepository.saveAndFlush(message)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
