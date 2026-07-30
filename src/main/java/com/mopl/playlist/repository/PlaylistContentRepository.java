package com.mopl.playlist.repository;

import com.mopl.playlist.entity.PlaylistContent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlaylistContentRepository extends JpaRepository<PlaylistContent, UUID> {

    boolean existsByPlaylistIdAndContentId(UUID playlistId, UUID contentId);

    List<PlaylistContent> findAllByPlaylistIdOrderByCreatedAtAsc(UUID playlistId);

    void deleteByPlaylistIdAndContentId(UUID playlistId, UUID contentId);
}