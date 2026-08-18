package com.mopl.watchingsession.dto;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.UserSummary;
import com.mopl.user.entity.User;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.presence.WatchingPresence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WatchingSessionDto (
    UUID id,
    UserSummary watcher,
    ContentSummary content,
    Instant createdAt
){
    public static WatchingSessionDto from(WatchingSessionSnapshot snapshot, User watcher, Content content) {
        return new WatchingSessionDto(
            snapshot.getId(),
            new UserSummary(watcher.getId(), watcher.getName(), watcher.getProfileImageUrl()),
            new ContentSummary(
                content.getId(),
                toApiContentType(content.getType()),
                content.getTitle(),
                content.getDescription(),
                content.getThumbnailUrl(),
                List.copyOf(content.getTags()),
                content.getAverageRating().doubleValue(),
                content.getReviewCount().intValue()),
            snapshot.getCreatedAt()
        );
    }

    public static WatchingSessionDto from(WatchingPresence presence, User watcher, Content content) {
        return new WatchingSessionDto(
            presence.snapshotId(),
            new UserSummary(watcher.getId(), watcher.getName(), watcher.getProfileImageUrl()),
            new ContentSummary(
                content.getId(),
                toApiContentType(content.getType()),
                content.getTitle(),
                content.getDescription(),
                content.getThumbnailUrl(),
                List.copyOf(content.getTags()),
                content.getAverageRating().doubleValue(),
                content.getReviewCount().intValue()),
            presence.startedAt()
        );
    }

    private static String toApiContentType(ContentType type) {
        return switch (type) {
            case MOVIE -> "movie";
            case TV_SERIES -> "tvSeries";
            case SPORT -> "sport";
        };
    }
}
