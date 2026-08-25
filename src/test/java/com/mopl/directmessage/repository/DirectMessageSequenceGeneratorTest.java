package com.mopl.directmessage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.directmessage.entity.Conversation;
import com.mopl.global.config.JpaConfig;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@ActiveProfiles("test")
@Import({
    JpaConfig.class,
    DirectMessageSequenceGenerator.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DirectMessageSequenceGeneratorTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    ConversationRepository conversationRepository;

    @Autowired
    DirectMessageSequenceGenerator sequenceGenerator;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        conversationRepository.deleteAll();
    }

    @Test
    @DisplayName("동시 메시지 전송에 대화별 순번을 중복 없이 할당")
    void next_concurrent_assignsUniqueSequence()
        throws Exception {

        // given
        Conversation conversation =
            conversationRepository.saveAndFlush(
                Conversation.create(
                    UUID.randomUUID(),
                    UUID.randomUUID()
                )
            );

        TransactionTemplate transactionTemplate =
            new TransactionTemplate(
                transactionManager
            );

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready =
            new CountDownLatch(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {
            Future<Long> firstResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return transactionTemplate.execute(
                        status ->
                            sequenceGenerator.next(
                                conversation.getId()
                            )
                    );
                });

            Future<Long> secondResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return transactionTemplate.execute(
                        status ->
                            sequenceGenerator.next(
                                conversation.getId()
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

            long firstSequence =
                firstResult.get(
                    5,
                    TimeUnit.SECONDS
                );

            long secondSequence =
                secondResult.get(
                    5,
                    TimeUnit.SECONDS
                );

            // then
            assertThat(
                Set.of(
                    firstSequence,
                    secondSequence
                )
            ).containsExactlyInAnyOrder(
                1L,
                2L
            );

            Conversation result =
                conversationRepository.findById(
                    conversation.getId()
                ).orElseThrow();

            assertThat(result.getNextMessageSequence())
                .isEqualTo(2L);
        } finally {
            executor.shutdownNow();
        }
    }
}
