package com.mopl.directmessage.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.directmessage.entity.ParticipantSlot;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    private static final UUID USER_ID_3 =
        UUID.fromString("33333333-3333-3333-3333-333333333333");

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

    @Autowired
    PlatformTransactionManager transactionManager;

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

    private void insertDirectMessage(
        UUID messageId,
        UUID conversationId,
        UUID senderId,
        long messageSequence,
        Instant createdAt
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO direct_messages (
                id,
                created_at,
                updated_at,
                conversation_id,
                sender_id,
                message_sequence,
                content,
                read_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
            """,
            messageId,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt),
            conversationId,
            senderId,
            messageSequence,
            "테스트 메시지"
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

        insertUser(
            USER_ID_3,
            "third@example.com",
            "third"
        );
    }

    //readAt 초기값 확인
    @Test
    @DisplayName("DM 저장 후 ID로 조회")
    void saveAndFindDirectMessage() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(
                Conversation.create(
                    USER_ID_1,
                    USER_ID_2
                )
            );

        ConversationParticipant participant =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            );

        participantRepository.saveAndFlush(participant);

        DirectMessage message = DirectMessage.create(
            conversation.getId(),
            USER_ID_1,
            1L,
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("동시에 DM을 읽음 처리하면 최초 요청 하나만 readAt을 기록")
    void markAsReadIfUnread_concurrent_updatesOnce() throws Exception {
        // given
        Conversation conversation =
            conversationRepository.save(
                Conversation.create(
                    USER_ID_1,
                    USER_ID_2
                )
            );

        participantRepository.save(
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            )
        );

        DirectMessage message =
            directMessageRepository.save(
                DirectMessage.create(
                    conversation.getId(),
                    USER_ID_1,
                    1L,
                    "동시 읽음 테스트"
                )
            );

        Instant firstReadAt =
            Instant.parse("2026-07-31T01:00:00Z");

        Instant secondReadAt =
            Instant.parse("2026-07-31T02:00:00Z");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        TransactionTemplate transactionTemplate =
            new TransactionTemplate(transactionManager);

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        try {
            Future<Integer> firstResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return transactionTemplate.execute(status ->
                        directMessageRepository.markAsReadIfUnread(
                            message.getId(),
                            conversation.getId(),
                            firstReadAt
                        )
                    );
                });

            Future<Integer> secondResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return transactionTemplate.execute(status ->
                        directMessageRepository.markAsReadIfUnread(
                            message.getId(),
                            conversation.getId(),
                            secondReadAt
                        )
                    );
                });

            ready.await();
            start.countDown();

            int firstUpdated = firstResult.get();
            int secondUpdated = secondResult.get();

            DirectMessage found =
                directMessageRepository.findById(message.getId())
                    .orElseThrow();

            Instant expectedReadAt =
                firstUpdated == 1
                    ? firstReadAt
                    : secondReadAt;

            // then
            assertThat(firstUpdated + secondUpdated)
                .isEqualTo(1);
            assertThat(found.getReadAt())
                .isEqualTo(expectedReadAt);
        } finally {
            executor.shutdown();

            jdbcTemplate.update(
                "DELETE FROM direct_messages WHERE conversation_id = ?",
                conversation.getId()
            );
            jdbcTemplate.update(
                "DELETE FROM conversation_participants WHERE conversation_id = ?",
                conversation.getId()
            );
            jdbcTemplate.update(
                "DELETE FROM conversations WHERE id = ?",
                conversation.getId()
            );
            jdbcTemplate.update(
                "DELETE FROM users WHERE id IN (?, ?, ?)",
                USER_ID_1,
                USER_ID_2,
                USER_ID_3
            );
        }
    }

    @Test
    @DisplayName("대화 참여자가 아닌 사용자는 DM을 저장할 수 없음")
    void saveDirectMessageByNonParticipantFails() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(
                Conversation.create(
                    USER_ID_1,
                    USER_ID_2
                )
            );

        ConversationParticipant participant =
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            );

        participantRepository.saveAndFlush(participant);

        DirectMessage message = DirectMessage.create(
            conversation.getId(),
            USER_ID_2,
            1L,
            "참여자가 아닌 사용자의 메시지"
        );

        // when & then
        assertThatThrownBy(
            () -> directMessageRepository.saveAndFlush(message)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DM을 생성 시간과 ID 기준으로 정렬하고 커서 조회")
    void findAllByCursor() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(
                Conversation.create(
                    USER_ID_1,
                    USER_ID_2
                )
            );

        Conversation otherConversation =
            conversationRepository.saveAndFlush(
                Conversation.create(
                    USER_ID_1,
                    USER_ID_3
                )
            );

        participantRepository.saveAllAndFlush(List.of(
            ConversationParticipant.create(
                conversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            ),
            ConversationParticipant.create(
                otherConversation.getId(),
                USER_ID_1,
                ParticipantSlot.FIRST
            )
        ));

        Instant firstTime = Instant.parse("2026-07-29T00:00:00Z");
        Instant secondTime = Instant.parse("2026-07-29T01:00:00.123456789Z");

        UUID messageId1 =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
        UUID messageId2 =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");
        UUID messageId3 =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");
        UUID otherMessageId =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1");

        insertDirectMessage(
            messageId1,
            conversation.getId(),
            USER_ID_1,
            1L,
            firstTime
        );
        insertDirectMessage(
            messageId2,
            conversation.getId(),
            USER_ID_1,
            2L,
            secondTime
        );
        insertDirectMessage(
            messageId3,
            conversation.getId(),
            USER_ID_1,
            3L,
            secondTime
        );
        insertDirectMessage(
            otherMessageId,
            otherConversation.getId(),
            USER_ID_1,
            1L,
            secondTime
        );

        entityManager.clear();

        // when
        List<DirectMessage> descending =
            directMessageRepository
                .findAllByConversationIdOrderByCreatedAtDescIdDesc(
                    conversation.getId(),
                    PageRequest.of(0, 10)
                );

        List<DirectMessage> afterCursor =
            directMessageRepository.findAllByCursorDesc(
                conversation.getId(),
                secondTime,
                messageId3,
                PageRequest.of(0, 10)
            );

        List<DirectMessage> ascending =
            directMessageRepository
                .findAllByConversationIdOrderByCreatedAtAscIdAsc(
                    conversation.getId(),
                    PageRequest.of(0, 10)
                );

        List<DirectMessage> ascendingAfterCursor =
            directMessageRepository.findAllByCursorAsc(
                conversation.getId(),
                secondTime,
                messageId2,
                PageRequest.of(0, 10)
            );

        long totalCount = directMessageRepository.countByConversationId(
            conversation.getId()
        );

        // then
        assertThat(descending)
            .extracting(DirectMessage::getId)
            .containsExactly(messageId3, messageId2, messageId1);

        assertThat(afterCursor)
            .extracting(DirectMessage::getId)
            .containsExactly(messageId2, messageId1);

        assertThat(ascending)
            .extracting(DirectMessage::getId)
            .containsExactly(messageId1, messageId2, messageId3);

        assertThat(ascendingAfterCursor)
            .extracting(DirectMessage::getId)
            .containsExactly(messageId3);

        assertThat(totalCount).isEqualTo(3L);
    }

    @Test
    @DisplayName("여러 대화의 최근 메시지와 미읽음 대화 ID를 일괄 조회")
    void findLatestMessagesAndUnreadConversationIds() {
        // given
        Conversation conversation1 =
            conversationRepository.saveAndFlush(
                Conversation.create(
                    USER_ID_1,
                    USER_ID_2
                )
            );

        Conversation conversation2 =
            conversationRepository.saveAndFlush(
                Conversation.create(
                    USER_ID_1,
                    USER_ID_3
                )
            );

        participantRepository.saveAllAndFlush(
            List.of(
                ConversationParticipant.create(
                    conversation1.getId(),
                    USER_ID_1,
                    ParticipantSlot.FIRST
                ),
                ConversationParticipant.create(
                    conversation1.getId(),
                    USER_ID_2,
                    ParticipantSlot.SECOND
                ),
                ConversationParticipant.create(
                    conversation2.getId(),
                    USER_ID_1,
                    ParticipantSlot.FIRST
                ),
                ConversationParticipant.create(
                    conversation2.getId(),
                    USER_ID_3,
                    ParticipantSlot.SECOND
                )
            )
        );

        UUID oldMessageId =
            UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
            );

        UUID latestMessageId =
            UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
            );

        UUID secondConversationMessageId =
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1"
            );

        Instant firstTime =
            Instant.parse("2026-08-01T00:00:00Z");

        Instant secondTime =
            Instant.parse("2026-08-01T01:00:00Z");

        insertDirectMessage(
            oldMessageId,
            conversation1.getId(),
            USER_ID_1,
            1L,
            firstTime
        );

        insertDirectMessage(
            latestMessageId,
            conversation1.getId(),
            USER_ID_2,
            2L,
            secondTime
        );

        insertDirectMessage(
            secondConversationMessageId,
            conversation2.getId(),
            USER_ID_1,
            1L,
            secondTime
        );

        entityManager.clear();

        List<UUID> conversationIds =
            List.of(
                conversation1.getId(),
                conversation2.getId()
            );

        // when
        List<DirectMessage> latestMessages =
            directMessageRepository
                .findLatestMessagesByConversationIds(
                    conversationIds
                );

        List<UUID> unreadConversationIds =
            directMessageRepository
                .findUnreadConversationIds(
                    conversationIds,
                    USER_ID_1
                );

        // then
        assertThat(latestMessages)
            .extracting(DirectMessage::getId)
            .containsExactlyInAnyOrder(
                latestMessageId,
                secondConversationMessageId
            );

        assertThat(unreadConversationIds)
            .containsExactly(
                conversation1.getId()
            );
    }

    @Test
    @DisplayName("마지막 SSE 이벤트 이후 수신한 DM만 Replay 대상으로 조회")
    void findAllReceivedForReplay_returnsReceivedMessages() {
        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(
                Conversation.create(
                    USER_ID_1,
                    USER_ID_2
                )
            );

        participantRepository.saveAllAndFlush(
            List.of(
                ConversationParticipant.create(
                    conversation.getId(),
                    USER_ID_1,
                    ParticipantSlot.FIRST
                ),
                ConversationParticipant.create(
                    conversation.getId(),
                    USER_ID_2,
                    ParticipantSlot.SECOND
                )
            )
        );

        UUID cursorId =
            UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
            );

        UUID receivedMessageId =
            UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
            );

        UUID sentMessageId =
            UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3"
            );

        Instant cursor =
            Instant.parse("2026-08-24T01:00:00Z");

        Instant later =
            Instant.parse("2026-08-24T02:00:00Z");

        insertDirectMessage(
            cursorId,
            conversation.getId(),
            USER_ID_2,
            1L,
            cursor
        );

        insertDirectMessage(
            receivedMessageId,
            conversation.getId(),
            USER_ID_2,
            2L,
            later
        );

        insertDirectMessage(
            sentMessageId,
            conversation.getId(),
            USER_ID_1,
            3L,
            later
        );

        entityManager.clear();

        // when
        List<DirectMessageReplayProjection> result =
            directMessageRepository
                .findAllReceivedForReplay(
                    USER_ID_1,
                    cursor,
                    cursorId,
                    PageRequest.of(0, 10)
                );

        // then
        assertThat(result).hasSize(1);

        DirectMessageReplayProjection message =
            result.get(0);

        assertThat(message.getId())
            .isEqualTo(receivedMessageId);

        assertThat(message.getSenderId())
            .isEqualTo(USER_ID_2);

        assertThat(message.getReceiverId())
            .isEqualTo(USER_ID_1);

        assertThat(message.getMessageSequence())
            .isEqualTo(2L);
    }
}
