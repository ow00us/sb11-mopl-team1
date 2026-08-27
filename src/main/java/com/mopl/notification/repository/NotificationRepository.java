package com.mopl.notification.repository;

import com.mopl.notification.entity.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Optional<Notification> findByIdAndReceiverId(
        UUID notificationId,
        UUID receiverId
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Notification notification
        SET notification.readAt = :readAt,
            notification.updatedAt = :readAt
        WHERE notification.id = :notificationId
            AND notification.receiverId = :receiverId
            AND notification.readAt IS NULL
        """)
    int markAsReadIfUnread(
        @Param("notificationId") UUID notificationId,
        @Param("receiverId") UUID receiverId,
        @Param("readAt") Instant readAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO notifications (
                id,
                created_at,
                updated_at,
                receiver_id,
                source_event_id,
                type,
                resource_id,
                source_entity_id,
                title,
                content,
                level
            )
            SELECT
                :notificationId,
                :createdAt,
                :createdAt,
                :receiverId,
                :sourceEventId,
                :type,
                :resourceId,
                :sourceEntityId,
                :title,
                :content,
                :level
            WHERE EXISTS (
                SELECT 1
                FROM users
                WHERE id = :receiverId
            )
            ON CONFLICT (
                source_event_id,
                receiver_id
            )
            WHERE source_event_id IS NOT NULL
            DO NOTHING
            """,
        nativeQuery = true
    )
    int insertIfAbsent(
        @Param("notificationId")
        UUID notificationId,

        @Param("createdAt")
        Instant createdAt,

        @Param("receiverId")
        UUID receiverId,

        @Param("sourceEventId")
        UUID sourceEventId,

        @Param("type")
        String type,

        @Param("resourceId")
        UUID resourceId,

        @Param("sourceEntityId")
        UUID sourceEntityId,

        @Param("title")
        String title,

        @Param("content")
        String content,

        @Param("level")
        String level
    );

    long countByReceiverIdAndReadAtIsNull(UUID receiverId);

    List<Notification> findByReceiverIdAndReadAtIsNull(
        UUID receiverId,
        Pageable pageable
    );

    @Query("""
        SELECT n
        FROM Notification n
        WHERE n.receiverId = :receiverId
          AND n.readAt IS NULL
          AND (
                n.createdAt < :cursor
                OR (
                    n.createdAt = :cursor
                    AND n.id < :idAfter
                )
          )
        ORDER BY n.createdAt DESC, n.id DESC
        """)
    List<Notification> findUnreadAfterDescending(
        @Param("receiverId") UUID receiverId,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );

    @Query("""
        SELECT n
        FROM Notification n
        WHERE n.receiverId = :receiverId
          AND n.readAt IS NULL
          AND (
                n.createdAt > :cursor
                OR (
                    n.createdAt = :cursor
                    AND n.id > :idAfter
                )
          )
        ORDER BY n.createdAt ASC, n.id ASC
        """)
    List<Notification> findUnreadAfterAscending(
        @Param("receiverId") UUID receiverId,
        @Param("cursor") Instant cursor,
        @Param("idAfter") UUID idAfter,
        Pageable pageable
    );

    @Query("""
        SELECT notification
        FROM Notification notification
        WHERE notification.receiverId = :receiverId
            AND (
                notification.createdAt > :cursor
                OR (
                    notification.createdAt = :cursor
                    AND notification.id > :idAfter
                )
            )
        ORDER BY notification.createdAt ASC,
                 notification.id ASC
        """)
    List<Notification> findAllForReplay(
        @Param("receiverId")
        UUID receiverId,

        @Param("cursor")
        Instant cursor,

        @Param("idAfter")
        UUID idAfter,

        Pageable pageable
    );
}
