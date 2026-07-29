package com.mopl.playlist.service;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.content.repository.ContentRepository;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.entity.PlaylistSubscription;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import org.springframework.dao.DataIntegrityViolationException;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaylistServiceImpl implements PlaylistService {

    private static final String SORT_UPDATED_AT      = "updatedAt";
    private static final String SORT_SUBSCRIBE_COUNT = "subscriberCount";
    private static final String DIRECTION_ASC        = "ASCENDING";

    private final PlaylistRepository playlistRepository;
    private final PlaylistSubscriptionRepository subscriptionRepository;
    private final PlaylistContentRepository playlistContentRepository;
    private final ContentRepository contentRepository;

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

    @Override
    public PlaylistDto get(UUID playlistId, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        boolean subscribedByMe = requesterId != null &&
                subscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, requesterId);
        List<ContentSummary> contents = loadContents(playlistId);
        return PlaylistDto.from(playlist, subscribedByMe, contents);
    }

    @Override
    public CursorResponse<PlaylistDto> getList(
            String keywordLike, UUID ownerIdEqual, UUID subscriberIdEqual,
            String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection,
            UUID requesterId) {

        int fetchSize = limit + 1;
        List<Playlist> rows = fetchPage(
                keywordLike, ownerIdEqual, subscriberIdEqual, cursor, idAfter, fetchSize, sortBy, sortDirection);

        boolean hasNext = rows.size() == fetchSize;
        List<Playlist> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor  = null;
        UUID   nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            Playlist last = page.get(page.size() - 1);
            nextCursor  = buildNextCursor(last, sortBy);
            nextIdAfter = last.getId();
        }

        Set<UUID> subscribedIds = Set.of();
        if (requesterId != null && !page.isEmpty()) {
            List<UUID> pageIds = page.stream().map(Playlist::getId).toList();
            subscribedIds = subscriptionRepository.findSubscribedPlaylistIds(requesterId, pageIds);
        }
        final Set<UUID> finalSubscribedIds = subscribedIds;
        List<PlaylistDto> data = page.stream()
                .map(p -> PlaylistDto.from(p, finalSubscribedIds.contains(p.getId()),
                        loadContents(p.getId())))
                .toList();

        String ownerIdStr      = ownerIdEqual      != null ? ownerIdEqual.toString()      : null;
        String subscriberIdStr = subscriberIdEqual  != null ? subscriberIdEqual.toString()  : null;
        long total = playlistRepository.countByFilter(keywordLike, ownerIdStr, subscriberIdStr);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, total, sortBy, sortDirection);
    }

    @Override
    @Transactional
    public PlaylistDto update(UUID playlistId, PlaylistUpdateRequest request, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        verifyOwner(playlist, requesterId);
        playlist.update(request.title(), request.description());
        return PlaylistDto.from(playlistRepository.saveAndFlush(playlist));
    }

    @Override
    @Transactional
    public void delete(UUID playlistId, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        verifyOwner(playlist, requesterId);
        playlistRepository.delete(playlist);
    }

    @Override
    @Transactional
    public void subscribe(UUID playlistId, UUID subscriberId) {
        Playlist playlist = findOrThrow(playlistId);
        if (playlist.isOwnedBy(subscriberId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (subscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, subscriberId)) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_DUPLICATE);
        }
        try {
            subscriptionRepository.saveAndFlush(
                    PlaylistSubscription.builder()
                            .playlistId(playlistId)
                            .subscriberId(subscriberId)
                            .build());
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_DUPLICATE);
        }
        playlistRepository.incrementSubscriberCount(playlistId);
    }

    @Override
    @Transactional
    public void unsubscribe(UUID playlistId, UUID subscriberId) {
        PlaylistSubscription subscription = subscriptionRepository
                .findByPlaylistIdAndSubscriberId(playlistId, subscriberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        subscriptionRepository.delete(subscription);
        subscriptionRepository.flush();
        playlistRepository.decrementSubscriberCount(subscription.getPlaylistId());
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
            String keywordLike, UUID ownerIdEqual, UUID subscriberIdEqual,
            String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {

        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        boolean isAsc         = DIRECTION_ASC.equalsIgnoreCase(sortDirection);
        String  ownerStr      = ownerIdEqual     != null ? ownerIdEqual.toString()     : null;
        String  subscriberStr = subscriberIdEqual != null ? subscriberIdEqual.toString() : null;
        String  idAfterStr    = idAfter           != null ? idAfter.toString()           : null;

        try {
            if (SORT_SUBSCRIBE_COUNT.equals(sortBy)) {
                Long cursorCount = (cursor != null) ? CursorUtils.decodeAsLong(cursor) : null;
                return isAsc
                        ? playlistRepository.findBySubscriberCountAsc(keywordLike, ownerStr, subscriberStr, cursorCount, idAfterStr, limit)
                        : playlistRepository.findBySubscriberCountDesc(keywordLike, ownerStr, subscriberStr, cursorCount, idAfterStr, limit);
            }

            Instant cursorTime = (cursor != null) ? CursorUtils.decodeAsInstant(cursor) : null;
            return isAsc
                    ? playlistRepository.findByUpdatedAtAsc(keywordLike, ownerStr, subscriberStr, cursorTime, idAfterStr, limit)
                    : playlistRepository.findByUpdatedAtDesc(keywordLike, ownerStr, subscriberStr, cursorTime, idAfterStr, limit);
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

    private List<ContentSummary> loadContents(UUID playlistId) {
        List<UUID> contentIds = playlistContentRepository
                .findAllByPlaylistIdOrderByCreatedAtAsc(playlistId)
                .stream()
                .map(pc -> pc.getContentId())
                .toList();
        if (contentIds.isEmpty()) return List.of();

        return contentRepository.findAllById(contentIds)
                .stream()
                .map(this::toContentSummary)
                .toList();
    }

    private ContentSummary toContentSummary(Content content) {
        return new ContentSummary(
                content.getId(),
                toApiType(content.getType()),
                content.getTitle(),
                content.getDescription(),
                content.getThumbnailUrl(),
                List.copyOf(content.getTags()),
                content.getAverageRating().doubleValue(),
                content.getReviewCount().intValue()
        );
    }

    private String toApiType(ContentType type) {
        return switch (type) {
            case MOVIE     -> "movie";
            case TV_SERIES -> "tvSeries";
            case SPORT     -> "sport";
        };
    }

    @Override
    @Transactional
    public void addContent(UUID playlistId, UUID contentId, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        verifyOwner(playlist, requesterId);
        if (!contentRepository.existsById(contentId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (playlistContentRepository.existsByPlaylistIdAndContentId(playlistId, contentId)) {
            return;
        }
        playlistContentRepository.save(PlaylistContent.create(playlistId, contentId));
    }

    @Override
    @Transactional
    public void removeContent(UUID playlistId, UUID contentId, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        verifyOwner(playlist, requesterId);
        if (!playlistContentRepository.existsByPlaylistIdAndContentId(playlistId, contentId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        playlistContentRepository.deleteByPlaylistIdAndContentId(playlistId, contentId);
    }
}
