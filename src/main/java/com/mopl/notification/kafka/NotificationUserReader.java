package com.mopl.notification.kafka;

import java.util.Optional;
import java.util.UUID;

public interface NotificationUserReader {

    Optional<String> findName(
        UUID userId
    );

    boolean exists(
        UUID userId
    );
}
