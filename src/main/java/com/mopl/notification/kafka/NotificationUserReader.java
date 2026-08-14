package com.mopl.notification.kafka;

import java.util.UUID;

public interface NotificationUserReader {

    String getName(UUID userId);
}
