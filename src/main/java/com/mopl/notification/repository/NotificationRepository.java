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

public interface NotificationRepository
    extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndReceiverId(
        UUID notificationId,
        UUID receiverId
    );

    @Modifying
    @Query("""
        UPDATE Notification notification
        SET notification.readAt = :readAt
        WHERE notification.id = :notificationId
            AND notification.receiverId = :receiverId
            AND notification.readAt IS NULL
        """)
    int markAsReadIfUnread(
        @Param("notificationId") UUID notificationId,
        @Param("receiverId") UUID receiverId,
        @Param("readAt") Instant readAt
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
}
