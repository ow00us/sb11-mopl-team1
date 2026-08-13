package com.mopl.playlist.service;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.ContentSummary;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.dto.SubscriberItemDto;
import com.mopl.global.common.UserSummary;
import com.mopl.playlist.entity.Playlist;
import com.mopl.playlist.entity.PlaylistContent;
import com.mopl.playlist.entity.PlaylistSubscription;
import com.mopl.playlist.repository.PlaylistContentRepository;
import com.mopl.playlist.repository.PlaylistRepository;
import com.mopl.playlist.repository.PlaylistSubscriptionRepository;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaylistServiceImpl implements PlaylistService {

    private static final String SORT_UPDATED_AT      = "updatedAt";
    private static final String SORT_SUBSCRIBE_COUNT = "subscriberCount";
    private static final String DIRECTION_ASC        = "ASCENDING";
    private static final String DIRECTION_DESC       = "DESCENDING";
    private static final String PG_UNIQUE_VIOLATION_SQLSTATE = "23505";
    private static final String UNKNOWN_USER_NAME = "알 수 없는 사용자";

    private final PlaylistRepository playlistRepository;
    private final PlaylistSubscriptionRepository subscriptionRepository;
    private final PlaylistContentRepository playlistContentRepository;
    private final ContentRepository contentRepository;
    private final PlaylistContentSaver playlistContentSaver;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public PlaylistDto create(PlaylistCreateRequest request, UUID ownerId) {
        Playlist playlist = Playlist.builder()
                .ownerId(ownerId)
                .title(request.title())
                .description(request.description())
                .build();
        Playlist saved = playlistRepository.save(playlist);
        return PlaylistDto.from(saved, toOwnerSummary(saved.getOwnerId()), false, List.of());
    }

    @Override
    public PlaylistDto get(UUID playlistId, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        boolean subscribedByMe = requesterId != null &&
                subscriptionRepository.existsByPlaylistIdAndSubscriberId(playlistId, requesterId);
        List<ContentSummary> contents = loadContents(playlistId);
        return PlaylistDto.from(playlist, toOwnerSummary(playlist.getOwnerId()), subscribedByMe, contents);
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

        List<UUID> pageIds = page.stream().map(Playlist::getId).toList();

        Set<UUID> subscribedIds = Set.of();
        if (requesterId != null && !pageIds.isEmpty()) {
            subscribedIds = subscriptionRepository.findSubscribedPlaylistIds(requesterId, pageIds);
        }
        final Set<UUID> finalSubscribedIds = subscribedIds;

        Map<UUID, List<ContentSummary>> contentsByPlaylistId = loadContentsBatch(pageIds);

        List<UUID> ownerIds = page.stream().map(Playlist::getOwnerId).distinct().toList();
        Map<UUID, UserSummary> ownersById = toUserSummaryMap(ownerIds);

        List<PlaylistDto> data = page.stream()
                .map(p -> PlaylistDto.from(
                        p,
                        ownersById.getOrDefault(p.getOwnerId(), unknownUserSummary(p.getOwnerId())),
                        finalSubscribedIds.contains(p.getId()),
                        contentsByPlaylistId.getOrDefault(p.getId(), List.of())))
                .toList();

        String ownerIdStr      = ownerIdEqual      != null ? ownerIdEqual.toString()      : null;
        String subscriberIdStr = subscriberIdEqual  != null ? subscriberIdEqual.toString()  : null;
        long total = playlistRepository.countByFilter(keywordLike, ownerIdStr, subscriberIdStr);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, total, sortBy, sortDirection);
    }

    @Override
    public CursorResponse<PlaylistDto> getPopular(
            String cursor, UUID idAfter, int limit, UUID requesterId) {

        // cursor·idAfter 는 짝으로만 유효. 한쪽만 있으면 부분 상태 방지 (기존 fetchPage 패턴)
        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Long cursorCount = null;
        Instant cursorUpdatedAt = null;
        if (cursor != null) {
            try {
                CursorUtils.PopularCursor decoded = CursorUtils.decodeAsPopularCursor(cursor);
                cursorCount = decoded.subscriberCount();
                cursorUpdatedAt = decoded.updatedAt();
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                // Instant.parse·Long.parseLong 실패는 잘못된 커서로 400 매핑 (getSubscribers 패턴)
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            }
        }

        int fetchSize = limit + 1;
        String idAfterStr = idAfter != null ? idAfter.toString() : null;
        List<Playlist> rows = playlistRepository.findPopular(
                cursorCount, cursorUpdatedAt, idAfterStr, fetchSize);

        boolean hasNext = rows.size() == fetchSize;
        List<Playlist> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor  = null;
        UUID   nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            Playlist last = page.get(page.size() - 1);
            nextCursor  = CursorUtils.encodePopularCursor(last.getSubscriberCount(), last.getUpdatedAt());
            nextIdAfter = last.getId();
        }

        List<UUID> pageIds = page.stream().map(Playlist::getId).toList();

        Set<UUID> subscribedIds = Set.of();
        if (requesterId != null && !pageIds.isEmpty()) {
            subscribedIds = subscriptionRepository.findSubscribedPlaylistIds(requesterId, pageIds);
        }
        final Set<UUID> finalSubscribedIds = subscribedIds;

        Map<UUID, List<ContentSummary>> contentsByPlaylistId = loadContentsBatch(pageIds);

        List<UUID> ownerIds = page.stream().map(Playlist::getOwnerId).distinct().toList();
        Map<UUID, UserSummary> ownersById = toUserSummaryMap(ownerIds);

        List<PlaylistDto> data = page.stream()
                .map(p -> PlaylistDto.from(
                        p,
                        ownersById.getOrDefault(p.getOwnerId(), unknownUserSummary(p.getOwnerId())),
                        finalSubscribedIds.contains(p.getId()),
                        contentsByPlaylistId.getOrDefault(p.getId(), List.of())))
                .toList();

        // 인기 랭킹은 필터가 없으므로 전체 카운트를 반환
        long total = playlistRepository.countByFilter(null, null, null);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, total,
                SORT_SUBSCRIBE_COUNT, DIRECTION_DESC);
    }

    @Override
    @Transactional
    public PlaylistDto update(UUID playlistId, PlaylistUpdateRequest request, UUID requesterId) {
        Playlist playlist = findOrThrow(playlistId);
        verifyOwner(playlist, requesterId);
        playlist.update(request.title(), request.description());
        Playlist saved = playlistRepository.saveAndFlush(playlist);
        List<ContentSummary> contents = loadContents(saved.getId());
        return PlaylistDto.from(saved, toOwnerSummary(saved.getOwnerId()), false, contents);
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
        // 예외 없는 upsert 로 삽입 시도 (ADR 2 - 204 멱등).
        // 이미 존재하면 rows=0 이 반환되고 트랜잭션은 abort 되지 않으므로,
        // 사전 exists 체크나 catch 블록 안에서의 재조회가 필요 없다.
        int inserted = subscriptionRepository.insertIfAbsent(
                playlistId.toString(), subscriberId.toString());
        if (inserted == 1) {
            // 엔티티 setter/증감 메서드 대신 원자적 SQL UPDATE 로 lost update 를 방지한다.
            // 신규 삽입 경로에서만 카운터를 증가시켜 중복 요청이 재증가시키지 않도록 한다.
            playlistRepository.incrementSubscriberCount(playlistId);
        }
    }

    @Override
    @Transactional
    public void unsubscribe(UUID playlistId, UUID subscriberId) {
        // 리소스 미존재는 멱등 대상이 아니므로 playlist 자체가 없으면 404 를 유지한다.
        findOrThrow(playlistId);
        // 실제 DELETE 는 rows affected 를 반환하는 네이티브 조건부 삭제로 실행한다.
        // 사전 exists 체크를 두지 않아 (재)취소가 자연스럽게 멱등이 되며,
        // 동일 (playlist, subscriber) 동시 unsubscribe 시에도 오직 하나만 rows=1 을 얻어
        // subscribe 의 조건부 increment 패턴과 대칭을 이룬다.
        int deleted = subscriptionRepository.deleteByPlaylistIdAndSubscriberIdReturningCount(
                playlistId.toString(), subscriberId.toString());
        if (deleted == 1) {
            playlistRepository.decrementSubscriberCount(playlistId);
        }
        // deleted == 0 은 (a) 애초에 구독이 없었거나 (b) 다른 트랜잭션이 먼저 삭제·감소한 race 경로.
        // 두 경우 모두 사용자 시점에서는 이미 구독이 없어졌으므로 조용히 성공으로 종료한다.
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
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            // Instant.parse 는 DateTimeParseException 을 던지므로 IllegalArgumentException 만
            // 잡으면 GlobalExceptionHandler catch-all 로 500 이 된다.
            // getSubscribers·FollowService·ContentServiceImpl 과 동일한 패턴으로 함께 잡는다.
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private String buildNextCursor(Playlist last, String sortBy) {
        if (SORT_SUBSCRIBE_COUNT.equals(sortBy)) {
            return CursorUtils.encodeLong(last.getSubscriberCount());
        }
        return CursorUtils.encodeInstant(last.getUpdatedAt());
    }

    // 단건 조회에서도 contents + content_tags 를 EntityGraph 로 한 번에 조회한다.
    // findAllById 를 사용하면 tags 가 콘텐츠별로 지연 로딩되어 N+1 이 발생하므로
    // loadContentsBatch 와 동일하게 findAllWithTagsByIdIn 을 사용한다.
    private List<ContentSummary> loadContents(UUID playlistId) {
        List<UUID> contentIds = playlistContentRepository
                .findAllByPlaylistIdOrderByCreatedAtAsc(playlistId)
                .stream()
                .map(PlaylistContent::getContentId)
                .toList();
        if (contentIds.isEmpty()) return List.of();

        Map<UUID, ContentSummary> summaryById = contentRepository.findAllWithTagsByIdIn(contentIds)
                .stream()
                .collect(Collectors.toMap(
                        Content::getId,
                        this::toContentSummary
                ));

        return contentIds.stream()
                .filter(summaryById::containsKey)
                .map(summaryById::get)
                .toList();
    }

    // 페이지 단위 배치 조회로 getList의 N+1을 방지한다.
    // playlist_contents 1회 + contents(+ 태그 EntityGraph 조인) 1회 = 페이지 크기와 무관하게 상수 쿼리로 완료한다.
    private Map<UUID, List<ContentSummary>> loadContentsBatch(List<UUID> playlistIds) {
        if (playlistIds.isEmpty()) return Map.of();

        List<PlaylistContent> links = playlistContentRepository
                .findAllByPlaylistIdInOrderByPlaylistIdAscCreatedAtAsc(playlistIds);
        if (links.isEmpty()) return Map.of();

        List<UUID> allContentIds = links.stream().map(PlaylistContent::getContentId).distinct().toList();
        Map<UUID, ContentSummary> summaryById = contentRepository.findAllWithTagsByIdIn(allContentIds)
                .stream()
                .collect(Collectors.toMap(
                        Content::getId,
                        this::toContentSummary
                ));

        Map<UUID, List<ContentSummary>> grouped = new LinkedHashMap<>();
        for (PlaylistContent link : links) {
            ContentSummary summary = summaryById.get(link.getContentId());
            if (summary == null) continue;
            grouped.computeIfAbsent(link.getPlaylistId(), k -> new ArrayList<>()).add(summary);
        }
        return grouped;
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

    // 단건 owner 조회. 배치 조회 API(findAllById)를 재사용해 진입점을 하나로 유지한다.
    private UserSummary toOwnerSummary(UUID ownerId) {
        return userRepository.findAllById(List.of(ownerId)).stream()
                .findFirst()
                .map(this::toUserSummary)
                .orElseGet(() -> unknownUserSummary(ownerId));
    }

    // 페이지 owner 배치 조회. findAllById 1회로 N+1을 방지한다.
    private Map<UUID, UserSummary> toUserSummaryMap(List<UUID> ownerIds) {
        if (ownerIds.isEmpty()) return Map.of();
        return userRepository.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(User::getId, this::toUserSummary));
    }

    private UserSummary toUserSummary(User user) {
        return new UserSummary(user.getId(), user.getName(), user.getProfileImageUrl());
    }

    // owner user 가 조회되지 않은 경우의 대체값 (ReviewServiceImpl 의 UNKNOWN_AUTHOR_NAME 정책과 동일)
    private UserSummary unknownUserSummary(UUID ownerId) {
        return new UserSummary(ownerId, UNKNOWN_USER_NAME, null);
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
        try {
            playlistContentSaver.save(playlistId, contentId);
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKeyViolation(e)) {
                throw e;
            }
        }
    }

    private boolean isDuplicateKeyViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        Throwable cause = e.getMostSpecificCause();
        return cause instanceof SQLException sql
                && PG_UNIQUE_VIOLATION_SQLSTATE.equals(sql.getSQLState());
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

    @Override
    public CursorResponse<SubscriberItemDto> getSubscribers(
            UUID playlistId, String cursor, UUID idAfter,
            int limit, String sortBy, String sortDirection) {
        // 존재하지 않는 플레이리스트는 404 (다른 목록 API 와 달리 리소스 소속 개념이 강해 사전 검증한다)
        findOrThrow(playlistId);

        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        Instant cursorTime;
        try {
            cursorTime = (cursor != null) ? CursorUtils.decodeAsInstant(cursor) : null;
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        int fetchSize = limit + 1;
        List<PlaylistSubscription> rows = subscriptionRepository.findByPlaylistIdDesc(
                playlistId.toString(),
                cursorTime,
                idAfter != null ? idAfter.toString() : null,
                fetchSize);

        boolean hasNext = rows.size() == fetchSize;
        List<PlaylistSubscription> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor  = null;
        UUID   nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            PlaylistSubscription last = page.get(page.size() - 1);
            nextCursor  = CursorUtils.encodeInstant(last.getCreatedAt());
            nextIdAfter = last.getId();
        }

        List<UUID> subscriberIds = page.stream().map(PlaylistSubscription::getSubscriberId).distinct().toList();
        Map<UUID, UserSummary> usersById = toUserSummaryMap(subscriberIds);

        List<SubscriberItemDto> data = page.stream()
                .map(s -> new SubscriberItemDto(
                        s.getId(),
                        usersById.getOrDefault(s.getSubscriberId(), unknownUserSummary(s.getSubscriberId())),
                        s.getCreatedAt()))
                .toList();

        long total = subscriptionRepository.countByPlaylistId(playlistId);
        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, total, sortBy, sortDirection);
    }
}
