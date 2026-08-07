package com.mopl.playlist.dto;

import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.UserSummary;
import com.mopl.playlist.entity.Playlist;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlaylistDto(
        UUID id,
        UserSummary owner,
        String title,
        String description,
        Instant updatedAt,
        long subscriberCount,
        boolean subscribedByMe,
        List<ContentSummary> contents
) {
    public static PlaylistDto from(Playlist playlist, boolean subscribedByMe, List<ContentSummary> contents) {
        return new PlaylistDto(
                playlist.getId(),
                new UserSummary(playlist.getOwnerId(), null, null),
                playlist.getTitle(),
                playlist.getDescription(),
                playlist.getUpdatedAt(),
                playlist.getSubscriberCount(),
                subscribedByMe,
                contents
        );
    }

    /** owner.name·profileImageUrl 은 User 도메인 연동 후 채워집니다. */
    public static PlaylistDto from(Playlist playlist) {
        return from(playlist, false, List.of());
    }
}