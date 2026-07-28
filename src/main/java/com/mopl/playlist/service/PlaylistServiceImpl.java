package com.mopl.playlist.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.repository.PlaylistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaylistServiceImpl implements PlaylistService {

    private static final String SORT_UPDATED_AT      = "updatedAt";
    private static final String SORT_SUBSCRIBE_COUNT = "subscribeCount";
    private static final String DIRECTION_ASC        = "ASCENDING";

    private final PlaylistRepository playlistRepository;

    /** 플레이리스트를 생성하고 저장한 결과를 반환합니다. */
    @Override
    @Transactional
    public PlaylistDto create(PlaylistCreateRequest request, UUID ownerId) {
        Playlist playlist = Playlist.builder()
                .ownerId(ownerId)
                .title(request.title())
                .description(request.description())
                .build();
        return PlaylistDto.from(playlistRepository.save(playlist));
    }

    /** 플레이리스트를 단건 조회합니다. 존재하지 않으면 RESOURCE_NOT_FOUND 예외를 발생시킵니다. */
    @Override
    public PlaylistDto get(UUID playlistId) {
        return PlaylistDto.from(findOrThrow(playlistId));
    }

    /** 커서 페이지네이션으로 플레이리스트 목록을 조회합니다. limit+1 조회로 다음 페이지 여부를 판단합니다. */
    @Override
    public CursorResponse<PlaylistDto> getList(
            String keywordLike, UUID ownerIdEqual, String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {

        int fetchSize = limit + 1;
        List<Playlist> rows = fetchPage(
                keywordLike, ownerIdEqual, cursor, idAfter, fetchSize, sortBy, sortDirection);

        boolean hasNext = rows.size() == fetchSize;
        List<Playlist> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor  = null;
        UUID   nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            Playlist last = page.get(page.size() - 1);
            nextCursor  = buildNextCursor(last, sortBy);
            nextIdAfter = last.getId();
        }

        List<PlaylistDto> data  = page.stream().map(PlaylistDto::from).toList();
        String ownerIdStr = ownerIdEqual != null ? ownerIdEqual.toString() : null;
        long total = playlistRepository.countByFilter(keywordLike, ownerIdStr);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, total, sortBy, sortDirection);
    }

    /** 소유자 검증 후 플레이리스트를 수정합니다. */
    @Override
    @Transactional
    public PlaylistDto update(UUID playlistId, PlaylistUpdateRequest request, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        verifyOwner(playlist, requesterId);
        playlist.update(request.title(), request.description());
        return PlaylistDto.from(playlistRepository.saveAndFlush(playlist));
    }

    /** 소유자 검증 후 플레이리스트를 삭제합니다. */
    @Override
    @Transactional
    public void delete(UUID playlistId, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        verifyOwner(playlist, requesterId);
        playlistRepository.delete(playlist);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────

    private Playlist findOrThrow(UUID playlistId) {
        return playlistRepository.findById(playlistId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private void verifyOwner(Playlist playlist, UUID requesterId) {
        if (!playlist.isOwnedBy(requesterId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private List<Playlist> fetchPage(
            String keywordLike, UUID ownerIdEqual,
            String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {

        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        boolean isAsc      = DIRECTION_ASC.equalsIgnoreCase(sortDirection);
        String  ownerStr   = ownerIdEqual != null ? ownerIdEqual.toString() : null;
        String  idAfterStr = idAfter      != null ? idAfter.toString()      : null;

        try {
            if (SORT_SUBSCRIBE_COUNT.equals(sortBy)) {
                Long cursorCount = (cursor != null) ? CursorUtils.decodeAsLong(cursor) : null;
                return isAsc
                        ? playlistRepository.findBySubscriberCountAsc(keywordLike, ownerStr, cursorCount, idAfterStr, limit)
                        : playlistRepository.findBySubscriberCountDesc(keywordLike, ownerStr, cursorCount, idAfterStr, limit);
            }

            Instant cursorTime = (cursor != null) ? CursorUtils.decodeAsInstant(cursor) : null;
            return isAsc
                    ? playlistRepository.findByUpdatedAtAsc(keywordLike, ownerStr, cursorTime, idAfterStr, limit)
                    : playlistRepository.findByUpdatedAtDesc(keywordLike, ownerStr, cursorTime, idAfterStr, limit);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private String buildNextCursor(Playlist last, String sortBy) {
        if (SORT_SUBSCRIBE_COUNT.equals(sortBy)) {
            return CursorUtils.encodeLong(last.getSubscriberCount());
        }
        return CursorUtils.encodeInstant(last.getUpdatedAt());
    }
}