package com.mopl.notification.kafka.payload;

import java.util.UUID;

public record FollowCreatedPayload(
    UUID followerId,
    UUID followeeId
) {
}
