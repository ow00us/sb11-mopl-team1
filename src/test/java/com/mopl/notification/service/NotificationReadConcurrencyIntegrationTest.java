package com.mopl.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.JpaConfig;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.repository.NotificationRepository;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    NotificationService.class
})
@AutoConfigureTestDatabase(
    replace = AutoConfigureTestDatabase.Replace.NONE
)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationReadConcurrencyIntegrationTest {

    private static final UUID RECEIVER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    NotificationRepository notificationRepository;

    @Autowired
    NotificationService notificationService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        insertUser(
            RECEIVER_ID,
            "receiver@example.com",
            "receiver"
        );
    }

    @AfterEach
    void tearDown() {
        notificationRepository.deleteAll();

        jdbcTemplate.update(
            "DELETE FROM users WHERE id = ?",
            RECEIVER_ID
        );
    }

    @Test
    @DisplayName("동시 조건부 UPDATE 중 한 요청만 readAt을 변경")
    void markAsReadIfUnread_concurrent_updatesOnce()
        throws Exception {

        // given
        Notification saved =
            notificationRepository.saveAndFlush(
                Notification.create(
                    RECEIVER_ID,
                    null,
                    "알림 제목",
                    "알림 내용",
                    NotificationLevel.INFO
                )
            );

        Instant firstReadAt =
            Instant.parse("2026-08-13T01:00:00Z");

        Instant secondReadAt =
            Instant.parse("2026-08-13T02:00:00Z");

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
            Future<Integer> firstResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return transactionTemplate.execute(
                        status ->
                            notificationRepository
                                .markAsReadIfUnread(
                                    saved.getId(),
                                    RECEIVER_ID,
                                    firstReadAt
                                )
                    );
                });

            Future<Integer> secondResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    return transactionTemplate.execute(
                        status ->
                            notificationRepository
                                .markAsReadIfUnread(
                                    saved.getId(),
                                    RECEIVER_ID,
                                    secondReadAt
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

            int firstUpdatedCount =
                firstResult.get(
                    5,
                    TimeUnit.SECONDS
                );

            int secondUpdatedCount =
                secondResult.get(
                    5,
                    TimeUnit.SECONDS
                );

            Notification result =
                notificationRepository.findById(
                    saved.getId()
                ).orElseThrow();

            Instant successfulReadAt =
                firstUpdatedCount == 1
                    ? firstReadAt
                    : secondReadAt;

            // then
            assertThat(
                firstUpdatedCount
                    + secondUpdatedCount
            ).isEqualTo(1);

            assertThat(result.getReadAt())
                .isEqualTo(successfulReadAt);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("동시 읽음 요청은 모두 성공하고 readAt을 기록")
    void read_concurrent_allRequestsSucceed()
        throws Exception {

        // given
        Notification saved =
            notificationRepository.saveAndFlush(
                Notification.create(
                    RECEIVER_ID,
                    null,
                    "알림 제목",
                    "알림 내용",
                    NotificationLevel.INFO
                )
            );

        ExecutorService executor =
            Executors.newFixedThreadPool(2);

        CountDownLatch ready =
            new CountDownLatch(2);

        CountDownLatch start =
            new CountDownLatch(1);

        try {
            Future<Void> firstResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    notificationService.read(
                        saved.getId(),
                        RECEIVER_ID
                    );

                    return null;
                });

            Future<Void> secondResult =
                executor.submit(() -> {
                    ready.countDown();
                    start.await();

                    notificationService.read(
                        saved.getId(),
                        RECEIVER_ID
                    );

                    return null;
                });

            assertThat(
                ready.await(
                    5,
                    TimeUnit.SECONDS
                )
            ).isTrue();

            start.countDown();

            firstResult.get(
                5,
                TimeUnit.SECONDS
            );

            secondResult.get(
                5,
                TimeUnit.SECONDS
            );

            Notification result =
                notificationRepository.findById(
                    saved.getId()
                ).orElseThrow();

            // then
            assertThat(result.getReadAt())
                .isNotNull();
        } finally {
            executor.shutdownNow();
        }
    }

    private void insertUser(
        UUID userId,
        String email,
        String name
    ) {
        Instant now =
            Instant.parse(
                "2026-08-13T00:00:00Z"
            );

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
}
