package com.mopl.directmessage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.directmessage.dto.ConversationCreateRequest;
import com.mopl.directmessage.dto.ConversationCreateResult;
import com.mopl.directmessage.entity.ConversationPairKey;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.ConversationRepository;
import com.mopl.global.config.JpaConfig;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    ConversationService.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConversationConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ConversationService conversationService;

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    ConversationParticipantRepository participantRepository;

    @Autowired
    UserRepository userRepository;

    private UUID firstUserId;
    private UUID secondUserId;

    @BeforeEach
    void setUp() {
        participantRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();

        User firstUser =
            userRepository.save(
                createUser(
                    "first@test.com",
                    "첫 번째 사용자"
                )
            );

        User secondUser =
            userRepository.save(
                createUser(
                    "second@test.com",
                    "두 번째 사용자"
                )
            );

        firstUserId = firstUser.getId();
        secondUserId = secondUser.getId();
    }

    @AfterEach
    void tearDown() {
        participantRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 사용자 쌍이 동시에 대화를 생성해도 한 건만 유지")
    void create_concurrent_sameUserPair_createsOnce()
        throws Exception {

        // given
        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready =
            new CountDownLatch(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {
            Future<ConversationCreateResult> firstResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return conversationService.create(
                        firstUserId,
                        new ConversationCreateRequest(
                            secondUserId
                        )
                    );
                });

            Future<ConversationCreateResult> secondResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return conversationService.create(
                        secondUserId,
                        new ConversationCreateRequest(
                            firstUserId
                        )
                    );
                });

            assertThat(
                ready.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            start.countDown();

            ConversationCreateResult first =
                firstResult.get(
                    10,
                    TimeUnit.SECONDS
                );

            ConversationCreateResult second =
                secondResult.get(
                    10,
                    TimeUnit.SECONDS
                );

            // then
            assertThat(
                Set.of(
                    first.created(),
                    second.created()
                )
            ).containsExactlyInAnyOrder(
                true,
                false
            );

            assertThat(first.conversation().id())
                .isEqualTo(second.conversation().id());

            assertThat(conversationRepository.count())
                .isEqualTo(1L);

            assertThat(
                participantRepository
                    .findAllByConversationId(
                        first.conversation().id()
                    )
            ).hasSize(2);

            assertThat(
                conversationRepository
                    .findByParticipantPairKey(
                        ConversationPairKey.create(
                            firstUserId,
                            secondUserId
                        )
                    )
            ).isPresent();
        } finally {
            executor.shutdownNow();
        }
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
}
