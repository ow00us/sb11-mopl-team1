package com.mopl.notification.repository;

import com.mopl.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndReceiverId(
        UUID notificationId,
        UUID receiverId
    );

    long countByReceiverIdAndReadAtIsNull(UUID receiverId);
}
