package com.mopl.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.JpaConfig;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
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
class NotificationRepositoryTest {

    private static final UUID RECEIVER_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    NotificationRepository notificationRepository;

    @BeforeEach
    void setUp() {
        Instant now = Instant.parse("2026-07-28T00:00:00Z");

        // TODO: User 엔티티가 병합되면 JdbcTemplate을 제거하고,
        //       TestEntityManager로 테스트용 User 엔티티를 저장하도록 변경한다.
        //       notifications.receiver_id에 users.id 외래키가 설정되어 있어 알림 저장 전에 사용자 행이 필요하다.
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
            RECEIVER_ID,
            Timestamp.from(now),
            Timestamp.from(now),
            "receiver@example.com",
            "password-hash",
            "receiver",
            "USER"
        );
    }

    @Test
    @DisplayName("알림을 저장하고 ID로 조회")
    void saveAndFindById_success() {
        // given
        Notification notification = Notification.create(
            RECEIVER_ID,
            null,
            "새로운 알림",
            "알림 내용",
            NotificationLevel.INFO
        );

        Notification saved =
            notificationRepository.saveAndFlush(notification);

        UUID notificationId = saved.getId();
        entityManager.clear();

        // when
        Optional<Notification> result =
            notificationRepository.findById(notificationId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getReceiverId()).isEqualTo(RECEIVER_ID);
        assertThat(result.get().getTitle()).isEqualTo("새로운 알림");
        assertThat(result.get().getContent()).isEqualTo("알림 내용");
        assertThat(result.get().getLevel())
            .isEqualTo(NotificationLevel.INFO);
        assertThat(result.get().getReadAt()).isNull();
        assertThat(result.get().getCreatedAt()).isNotNull();
        assertThat(result.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("알림 ID와 수신자 ID로 본인의 알림을 조회")
    void findByIdAndReceiverId_success() {
        // given
        Notification saved = notificationRepository.saveAndFlush(
            Notification.create(
                RECEIVER_ID,
                null,
                "알림 제목",
                "알림 내용",
                NotificationLevel.INFO
            )
        );

        entityManager.clear();

        // when
        Optional<Notification> result =
            notificationRepository.findByIdAndReceiverId(
                saved.getId(),
                RECEIVER_ID
            );

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getReceiverId()).isEqualTo(RECEIVER_ID);
    }

    @Test
    @DisplayName("다른 사용자의 알림은 ID와 수신자 조건으로 조회되지 않음")
    void findByIdAndReceiverId_otherReceiver_returnsEmpty() {
        // given
        Notification saved = notificationRepository.saveAndFlush(
            Notification.create(
                RECEIVER_ID,
                null,
                "알림 제목",
                "알림 내용",
                NotificationLevel.INFO
            )
        );

        entityManager.clear();

        // when
        Optional<Notification> result =
            notificationRepository.findByIdAndReceiverId(
                saved.getId(),
                UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
                )
            );

        // then
        assertThat(result).isEmpty();
    }
}
