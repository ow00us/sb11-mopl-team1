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

    @Column(nullable = false, length = 255)
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

    /**
     * 제목과 설명을 부분 수정합니다.
     * {@code null} 또는 빈 문자열/공백만 있는 값은 무시되고 기존값이 유지됩니다.
     * <p>
     * 단, title 은 {@code PlaylistUpdateRequest} 의 {@code @Size(max = 255)} 가
     * 컨트롤러 단에서 먼저 검증되므로 256자 이상 문자열(공백 포함)은
     * 이 메서드에 도달하기 전에 400 으로 거절됩니다.
     * <p>
     * 이 계약은 {@code openapi/mopl-api.yaml} 의 {@code PlaylistUpdateRequest} 스키마와 동기화되어야 합니다.
     */
    public void update(String title, String description) {
        if (title != null && !title.isBlank()) this.title = title;
        if (description != null && !description.isBlank()) this.description = description;
    }

    /** 주어진 userId 가 이 플레이리스트의 소유자인지 확인합니다. */
    public boolean isOwnedBy(UUID userId) {
        return this.ownerId.equals(userId);
    }
}