package com.mopl.playlist.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.repository.PlaylistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaylistServiceImpl implements PlaylistService {

    private final PlaylistRepository playlistRepository;

    @Override
    @Transactional
    public PlaylistDto create(PlaylistCreateRequest request, UUID ownerId) {
        throw new UnsupportedOperationException("미구현");
    }

    @Override
    public PlaylistDto get(UUID playlistId) {
        throw new UnsupportedOperationException("미구현");
    }

    @Override
    public CursorResponse<PlaylistDto> getList(
            String keywordLike, UUID ownerIdEqual, String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {
        throw new UnsupportedOperationException("미구현");
    }

    @Override
    @Transactional
    public PlaylistDto update(UUID playlistId, PlaylistUpdateRequest request, UUID requesterId) {
        throw new UnsupportedOperationException("미구현");
    }

    @Override
    @Transactional
    public void delete(UUID playlistId, UUID requesterId) {
        throw new UnsupportedOperationException("미구현");
    }
}