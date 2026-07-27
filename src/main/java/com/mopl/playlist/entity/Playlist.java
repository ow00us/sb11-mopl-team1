package com.mopl.playlist.entity;

import com.mopl.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Getter
@Table(name = "playlists")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Playlist extends BaseEntity {

    @Column(nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private long subscriberCount = 0;

    @Builder
    public Playlist(UUID ownerId, String title, String description) {
        this.ownerId = ownerId;
        this.title = title;
        this.description = description;
    }

    public void update(String title, String description) {
        throw new UnsupportedOperationException("미구현");
    }

    public boolean isOwnedBy(UUID userId) {
        throw new UnsupportedOperationException("미구현");
    }

    public void incrementSubscriberCount() {
        throw new UnsupportedOperationException("미구현");
    }

    public void decrementSubscriberCount() {
        throw new UnsupportedOperationException("미구현");
    }
}