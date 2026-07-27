package com.mopl.playlist.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;

import java.util.UUID;

public interface PlaylistService {

    PlaylistDto create(PlaylistCreateRequest request, UUID ownerId);

    PlaylistDto get(UUID playlistId);

    CursorResponse<PlaylistDto> getList(
            String keywordLike,
            UUID ownerIdEqual,
            String cursor,
            UUID idAfter,
            int limit,
            String sortBy,
            String sortDirection
    );

    PlaylistDto update(UUID playlistId, PlaylistUpdateRequest request, UUID requesterId);

    void delete(UUID playlistId, UUID requesterId);
}