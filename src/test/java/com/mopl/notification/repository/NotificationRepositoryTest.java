package com.mopl.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.global.config.JpaConfig;
import com.mopl.notification.entity.Notification;
import com.mopl.notification.entity.NotificationLevel;
import com.mopl.notification.entity.NotificationType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    private static final UUID OTHER_RECEIVER_ID = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    private static final UUID NOTIFICATION_ID_1 = UUID.fromString(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"
    );

    private static final UUID NOTIFICATION_ID_2 = UUID.fromString(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2"
    );

    private static final UUID NOTIFICATION_ID_3 = UUID.fromString(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3"
    );

    private static final UUID NOTIFICATION_ID_4 = UUID.fromString(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa4"
    );

    private static final UUID NOTIFICATION_ID_5 = UUID.fromString(
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa5"
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

        insertUser(
            RECEIVER_ID,
            "receiver@example.com",
            "receiver"
        );

        insertUser(
            OTHER_RECEIVER_ID,
            "other@example.com",
            "other"
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
        assertThat(result.get().getReceiverId())
            .isEqualTo(RECEIVER_ID);
        assertThat(result.get().getTitle())
            .isEqualTo("새로운 알림");
        assertThat(result.get().getContent())
            .isEqualTo("알림 내용");
        assertThat(result.get().getLevel())
            .isEqualTo(NotificationLevel.INFO);
        assertThat(result.get().getReadAt()).isNull();
        assertThat(result.get().getCreatedAt()).isNotNull();
        assertThat(result.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("알림 유형과 대상 정보를 저장하고 조회")
    void saveAndFindByTargetInformation_success() {
        // given
        UUID sourceEventId = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
        );

        UUID resourceId = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
        );

        UUID sourceEntityId = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
        );

        Notification notification = Notification.create(
            RECEIVER_ID,
            sourceEventId,
            NotificationType.DIRECT_MESSAGE,
            resourceId,
            sourceEntityId,
            "새로운 DM",
            "메시지가 도착했습니다.",
            NotificationLevel.INFO
        );

        Notification saved = notificationRepository.saveAndFlush(notification);

        UUID notificationId = saved.getId();
        entityManager.clear();

        // when
        Notification result = notificationRepository.findById(notificationId)
            .orElseThrow();

        // then
        assertThat(result.getSourceEventId())
            .isEqualTo(sourceEventId);

        assertThat(result.getType())
            .isEqualTo(NotificationType.DIRECT_MESSAGE);

        assertThat(result.getResourceId())
            .isEqualTo(resourceId);

        assertThat(result.getSourceEntityId())
            .isEqualTo(sourceEntityId);
    }

    @Test
    @DisplayName("알림 ID와 수신자 ID가 모두 일치하는 경우에만 조회")
    void findByIdAndReceiverId_matchesOwner() {
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
        Optional<Notification> ownerResult =
            notificationRepository.findByIdAndReceiverId(
                saved.getId(),
                RECEIVER_ID
            );

        Optional<Notification> otherResult =
            notificationRepository.findByIdAndReceiverId(
                saved.getId(),
                OTHER_RECEIVER_ID
            );

        // then
        assertThat(ownerResult).isPresent();
        assertThat(otherResult).isEmpty();
    }

    @Test
    @DisplayName("알림 읽음 처리를 반복하면 최초 readAt을 유지")
    void markAsReadIfUnread_repeated_preservesFirstReadAt() {
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

        // when
        int firstUpdatedCount =
            notificationRepository.markAsReadIfUnread(
                saved.getId(),
                RECEIVER_ID,
                firstReadAt
            );

        int secondUpdatedCount =
            notificationRepository.markAsReadIfUnread(
                saved.getId(),
                RECEIVER_ID,
                secondReadAt
            );

        entityManager.clear();

        Notification result =
            notificationRepository.findById(
                saved.getId()
            ).orElseThrow();

        // then
        assertThat(firstUpdatedCount)
            .isEqualTo(1);

        assertThat(secondUpdatedCount)
            .isZero();

        assertThat(result.getReadAt())
            .isEqualTo(firstReadAt);

        assertThat(result.getUpdatedAt())
            .isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("수신자의 읽지 않은 알림만 최신순으로 조회")
    void findUnread_firstPage_descending() {
        // given
        insertNotification(
            NOTIFICATION_ID_1,
            RECEIVER_ID,
            "오래된 미읽음 알림",
            Instant.parse("2026-07-28T01:00:00Z"),
            null
        );

        insertNotification(
            NOTIFICATION_ID_2,
            RECEIVER_ID,
            "최신 미읽음 알림",
            Instant.parse("2026-07-28T03:00:00Z"),
            null
        );

        insertNotification(
            NOTIFICATION_ID_3,
            RECEIVER_ID,
            "읽은 알림",
            Instant.parse("2026-07-28T04:00:00Z"),
            Instant.parse("2026-07-28T05:00:00Z")
        );

        insertNotification(
            NOTIFICATION_ID_4,
            OTHER_RECEIVER_ID,
            "다른 사용자의 알림",
            Instant.parse("2026-07-28T06:00:00Z"),
            null
        );

        // when
        List<Notification> result =
            notificationRepository.findByReceiverIdAndReadAtIsNull(
                RECEIVER_ID,
                firstPage(Sort.Direction.DESC)
            );

        long totalCount =
            notificationRepository.countByReceiverIdAndReadAtIsNull(
                RECEIVER_ID
            );

        // then
        assertThat(result)
            .extracting(Notification::getId)
            .containsExactly(
                NOTIFICATION_ID_2,
                NOTIFICATION_ID_1
            );

        assertThat(totalCount).isEqualTo(2L);
    }

    @Test
    @DisplayName("수신자의 읽지 않은 알림을 오래된순으로 조회")
    void findUnread_firstPage_ascending() {
        // given
        insertNotification(
            NOTIFICATION_ID_1,
            RECEIVER_ID,
            "오래된 알림",
            Instant.parse("2026-07-28T01:00:00Z"),
            null
        );

        insertNotification(
            NOTIFICATION_ID_2,
            RECEIVER_ID,
            "중간 알림",
            Instant.parse("2026-07-28T02:00:00Z"),
            null
        );

        insertNotification(
            NOTIFICATION_ID_3,
            RECEIVER_ID,
            "최신 알림",
            Instant.parse("2026-07-28T03:00:00Z"),
            null
        );

        // when
        List<Notification> result =
            notificationRepository.findByReceiverIdAndReadAtIsNull(
                RECEIVER_ID,
                firstPage(Sort.Direction.ASC)
            );

        // then
        assertThat(result)
            .extracting(Notification::getId)
            .containsExactly(
                NOTIFICATION_ID_1,
                NOTIFICATION_ID_2,
                NOTIFICATION_ID_3
            );
    }

    @Test
    @DisplayName("최신순 조회 시 생성 시간과 ID 커서 이후의 알림만 조회")
    void findUnread_afterCursor_descending() {
        // given
        Instant sameCreatedAt =
            Instant.parse("2026-07-28T03:00:00Z");

        insertNotification(
            NOTIFICATION_ID_1,
            RECEIVER_ID,
            "동시 생성 알림 1",
            sameCreatedAt,
            null
        );

        insertNotification(
            NOTIFICATION_ID_2,
            RECEIVER_ID,
            "동시 생성 알림 2",
            sameCreatedAt,
            null
        );

        insertNotification(
            NOTIFICATION_ID_3,
            RECEIVER_ID,
            "동시 생성 알림 3",
            sameCreatedAt,
            null
        );

        insertNotification(
            NOTIFICATION_ID_4,
            RECEIVER_ID,
            "더 오래된 알림",
            Instant.parse("2026-07-28T02:00:00Z"),
            null
        );

        insertNotification(
            NOTIFICATION_ID_5,
            RECEIVER_ID,
            "더 최신 알림",
            Instant.parse("2026-07-28T04:00:00Z"),
            null
        );

        // when
        List<Notification> result =
            notificationRepository.findUnreadAfterDescending(
                RECEIVER_ID,
                sameCreatedAt,
                NOTIFICATION_ID_2,
                PageRequest.of(0, 10)
            );

        // then
        assertThat(result)
            .extracting(Notification::getId)
            .containsExactly(
                NOTIFICATION_ID_1,
                NOTIFICATION_ID_4
            );
    }

    @Test
    @DisplayName("오래된순 조회 시 생성 시간과 ID 커서 이후의 알림만 조회")
    void findUnread_afterCursor_ascending() {
        // given
        Instant sameCreatedAt =
            Instant.parse("2026-07-28T03:00:00Z");

        insertNotification(
            NOTIFICATION_ID_1,
            RECEIVER_ID,
            "동시 생성 알림 1",
            sameCreatedAt,
            null
        );

        insertNotification(
            NOTIFICATION_ID_2,
            RECEIVER_ID,
            "동시 생성 알림 2",
            sameCreatedAt,
            null
        );

        insertNotification(
            NOTIFICATION_ID_3,
            RECEIVER_ID,
            "동시 생성 알림 3",
            sameCreatedAt,
            null
        );

        insertNotification(
            NOTIFICATION_ID_4,
            RECEIVER_ID,
            "더 오래된 알림",
            Instant.parse("2026-07-28T02:00:00Z"),
            null
        );

        insertNotification(
            NOTIFICATION_ID_5,
            RECEIVER_ID,
            "더 최신 알림",
            Instant.parse("2026-07-28T04:00:00Z"),
            null
        );

        // when
        List<Notification> result =
            notificationRepository.findUnreadAfterAscending(
                RECEIVER_ID,
                sameCreatedAt,
                NOTIFICATION_ID_2,
                PageRequest.of(0, 10)
            );

        // then
        assertThat(result)
            .extracting(Notification::getId)
            .containsExactly(
                NOTIFICATION_ID_3,
                NOTIFICATION_ID_5
            );
    }

    private PageRequest firstPage(Sort.Direction direction) {
        Sort sort = Sort.by(direction, "createdAt")
            .and(Sort.by(direction, "id"));

        return PageRequest.of(0, 10, sort);
    }

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

    private void insertNotification(
        UUID notificationId,
        UUID receiverId,
        String title,
        Instant createdAt,
        Instant readAt
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO notifications (
                id,
                created_at,
                updated_at,
                receiver_id,
                title,
                content,
                level,
                read_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            notificationId,
            Timestamp.from(createdAt),
            Timestamp.from(createdAt),
            receiverId,
            title,
            title + " 내용",
            "INFO",
            readAt == null ? null : Timestamp.from(readAt)
        );

        entityManager.clear();
    }
}
