package com.mopl.playlist.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaylistContentTest {

    @Test
    @DisplayName("PlaylistContent 생성 시 playlistId와 contentId가 저장된다")
    void create() {
        UUID playlistId = UUID.randomUUID();
        UUID contentId = UUID.randomUUID();

        PlaylistContent playlistContent = PlaylistContent.create(playlistId, contentId);

        assertThat(playlistContent.getPlaylistId()).isEqualTo(playlistId);
        assertThat(playlistContent.getContentId()).isEqualTo(contentId);
    }
}