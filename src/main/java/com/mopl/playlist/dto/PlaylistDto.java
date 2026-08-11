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
    public static PlaylistDto from(
            Playlist playlist,
            UserSummary owner,
            boolean subscribedByMe,
            List<ContentSummary> contents
    ) {
        return new PlaylistDto(
                playlist.getId(),
                owner,
                playlist.getTitle(),
                playlist.getDescription(),
                playlist.getUpdatedAt(),
                playlist.getSubscriberCount(),
                subscribedByMe,
                contents
        );
    }
}