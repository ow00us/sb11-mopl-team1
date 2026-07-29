package com.mopl.playlist.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@Table(
        name = "playlist_contents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_playlist_contents_playlist_content",
                columnNames = {"playlist_id", "content_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaylistContent extends BaseEntity {

    @Column(name = "playlist_id", nullable = false)
    private UUID playlistId;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    private PlaylistContent(UUID playlistId, UUID contentId) {
        this.playlistId = playlistId;
        this.contentId = contentId;
    }

    public static PlaylistContent create(UUID playlistId, UUID contentId) {
        return new PlaylistContent(playlistId, contentId);
    }
}