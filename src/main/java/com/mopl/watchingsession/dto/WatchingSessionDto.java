package com.mopl.watchingsession.dto;

import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import java.time.Instant;
import java.util.UUID;

public record WatchingSessionDto (
    UUID id,
    UUID watcherId,
    UUID contentId,
    Instant expiresAt
){
    public static WatchingSessionDto from(WatchingSessionSnapshot snapshot) {
        return new WatchingSessionDto(
            snapshot.getId(),
            snapshot.getWatcherId(),
            snapshot.getContentId(),
            snapshot.getExpiresAt()
        );
    }
}
