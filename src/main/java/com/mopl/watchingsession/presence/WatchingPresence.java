package com.mopl.watchingsession.presence;

import java.time.Instant;
import java.util.UUID;

public record WatchingPresence(
    UUID snapshotId,
    UUID watcherId,
    UUID contentId,
    String sessionId,
    String subscriptionId,
    Instant startedAt
) {
}
