package com.mopl.directmessage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationPairKey;
import com.mopl.global.config.JpaConfig;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
public class ConversationRepositoryTest {

    private static final UUID USER_ID_1 =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID USER_ID_2 =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    ConversationRepository conversationRepository;

    @Test
    @DisplayName("대화 저장 후 ID로 조회")
    void saveAndGetConversationId() {
        // given
        Conversation conversation =
            Conversation.create(
                USER_ID_1,
                USER_ID_2
            );

        // when
        Conversation saved =
            conversationRepository.saveAndFlush(
                conversation
            );

        entityManager.clear();

        Conversation found =
            conversationRepository.findById(
                saved.getId()
            ).orElseThrow();

        // then
        assertThat(found.getId())
            .isEqualTo(saved.getId());

        assertThat(found.getParticipantPairKey())
            .isEqualTo(
                ConversationPairKey.create(
                    USER_ID_1,
                    USER_ID_2
                )
            );

        assertThat(found.getCreatedAt())
            .isNotNull();

        assertThat(found.getUpdatedAt())
            .isNotNull();
    }

    @Test
    @DisplayName("사용자 순서와 관계없이 동일한 Pair Key를 생성")
    void create_reversedUsers_createsSamePairKey() {
        // when
        Conversation forward =
            Conversation.create(
                USER_ID_1,
                USER_ID_2
            );

        Conversation reverse =
            Conversation.create(
                USER_ID_2,
                USER_ID_1
            );

        // then
        assertThat(forward.getParticipantPairKey())
            .isEqualTo(
                reverse.getParticipantPairKey()
            );
    }

    @Test
    @DisplayName("동일 사용자 쌍의 대화를 중복 저장할 수 없음")
    void save_duplicatePairKey_fails() {
        // given
        conversationRepository.saveAndFlush(
            Conversation.create(
                USER_ID_1,
                USER_ID_2
            )
        );

        entityManager.clear();

        Conversation duplicate =
            Conversation.create(
                USER_ID_2,
                USER_ID_1
            );

        // when & then
        assertThatThrownBy(() ->
            conversationRepository.saveAndFlush(
                duplicate
            )
        ).isInstanceOf(
            DataIntegrityViolationException.class
        );
    }
}
