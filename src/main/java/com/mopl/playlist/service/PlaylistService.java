package com.mopl.playlist.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;

import java.util.UUID;

/** 플레이리스트 생성·조회·수정·삭제 비즈니스 로직을 정의하는 인터페이스입니다. */
public interface PlaylistService {

    /** 플레이리스트를 생성하고 결과를 반환합니다. */
    PlaylistDto create(PlaylistCreateRequest request, UUID ownerId);

    /** 플레이리스트를 단건 조회합니다. 존재하지 않으면 예외를 발생시킵니다. */
    PlaylistDto get(UUID playlistId);

    /** 커서 페이지네이션으로 플레이리스트 목록을 조회합니다. */
    CursorResponse<PlaylistDto> getList(
            String keywordLike,
            UUID ownerIdEqual,
            String cursor,
            UUID idAfter,
            int limit,
            String sortBy,
            String sortDirection
    );

    /** 소유자 검증 후 플레이리스트를 수정합니다. */
    PlaylistDto update(UUID playlistId, PlaylistUpdateRequest request, UUID requesterId);

    /** 소유자 검증 후 플레이리스트를 삭제합니다. */
    void delete(UUID playlistId, UUID requesterId);
}