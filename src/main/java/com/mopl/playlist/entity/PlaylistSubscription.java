package com.mopl.playlist.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@Table(
        name = "playlist_subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_playlist_subscriptions_playlist_subscriber",
                columnNames = {"playlist_id", "subscriber_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaylistSubscription extends BaseEntity {

    @Column(nullable = false)
    private UUID playlistId;

    @Column(nullable = false)
    private UUID subscriberId;

    @Builder
    public PlaylistSubscription(UUID playlistId, UUID subscriberId) {
        this.playlistId = playlistId;
        this.subscriberId = subscriberId;
    }
}
