package com.mopl.notification.kafka.payload;

import java.util.UUID;

public record PlaylistSubscriptionCreatedPayload (
    UUID playlistId,
    UUID playlistOwnerId,
    UUID subscriberId
) {
}
