package com.mopl.directmessage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.directmessage.entity.Conversation;
import com.mopl.global.config.JpaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
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
        //given
        Conversation conversation = Conversation.create();

        //when
        Conversation saved = conversationRepository.saveAndFlush(conversation);
        entityManager.clear();

        Conversation found = conversationRepository.findById(saved.getId())
            .orElseThrow();

        //then
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }
}
