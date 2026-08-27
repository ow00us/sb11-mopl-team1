package com.mopl.sse.service;

import java.time.Instant;
import java.util.UUID;

public record SseEventPosition(
    UUID eventId,
    Instant createdAt
) {
}
