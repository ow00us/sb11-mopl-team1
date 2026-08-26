package com.mopl.playlist.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.dto.SubscriberItemDto;

import java.util.UUID;

/** 플레이리스트 CRUD 및 구독 비즈니스 로직을 정의하는 인터페이스입니다. */
public interface PlaylistService {

    PlaylistDto create(PlaylistCreateRequest request, UUID ownerId);

    /** 단건 조회합니다. requesterId 가 null 이면 subscribedByMe 는 false 입니다. */
    PlaylistDto get(UUID playlistId, UUID requesterId);

    /** 커서 페이지네이션으로 목록을 조회합니다. requesterId 가 null 이면 subscribedByMe 는 false 입니다. */
    CursorResponse<PlaylistDto> getList(
            String keywordLike,
            UUID ownerIdEqual,
            UUID subscriberIdEqual,
            String cursor,
            UUID idAfter,
            int limit,
            String sortBy,
            String sortDirection,
            UUID requesterId
    );

    /** 인기 랭킹(subscriber_count DESC → updated_at DESC → id DESC) 전용 목록 조회입니다. 필터 파라미터는 노출하지 않습니다. */
    CursorResponse<PlaylistDto> getPopular(
            String cursor,
            UUID idAfter,
            int limit,
            UUID requesterId
    );

    PlaylistDto update(UUID playlistId, PlaylistUpdateRequest request, UUID requesterId);

    void delete(UUID playlistId, UUID requesterId);

    /** 플레이리스트를 구독합니다. 소유자 구독 시 403, 중복 시 409 를 발생시킵니다. */
    void subscribe(UUID playlistId, UUID subscriberId);

    /** 플레이리스트 구독을 취소합니다. 구독 정보가 없으면 404 를 발생시킵니다. */
    void unsubscribe(UUID playlistId, UUID subscriberId);

    /** 플레이리스트에 콘텐츠를 추가합니다. 소유자가 아니면 403, 중복이면 무시합니다. */
    void addContent(UUID playlistId, UUID contentId, UUID requesterId);

    /** 플레이리스트에서 콘텐츠를 삭제합니다. 소유자가 아니면 403, 없으면 404 를 발생시킵니다. */
    void removeContent(UUID playlistId, UUID contentId, UUID requesterId);

    /** 특정 플레이리스트의 구독자 목록을 커서 페이지네이션으로 조회합니다. 없는 플레이리스트면 404. */
    CursorResponse<SubscriberItemDto> getSubscribers(
            UUID playlistId,
            String cursor,
            UUID idAfter,
            int limit,
            String sortBy,
            String sortDirection
    );
}