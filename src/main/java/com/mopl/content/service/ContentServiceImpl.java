package com.mopl.content.service;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.ContentSearchDeleteEvent;
import com.mopl.content.search.ContentSearchSyncEvent;
import com.mopl.content.storage.ThumbnailStorage;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.watchingsession.repository.ContentWatcherCountView;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentServiceImpl implements ContentService {

    private static final String SORT_CREATED_AT = "createdAt";
    private static final String SORT_WATCHER_COUNT = "watcherCount";
    private static final String SORT_AVERAGE_RATING = "averageRating";
    private static final String DIRECTION_ASC = "ASCENDING";

    private final ContentRepository contentRepository;
    private final ThumbnailStorage thumbnailStorage;
    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public ContentDto create(ContentCreateRequest request, MultipartFile thumbnail) {
        Content content = Content.builder()
                .type(request.type())
                .source(ContentSource.MANUAL)
                .title(request.title())
                .description(request.description())
                .build();
        request.tags().forEach(content::addTag);
        if (thumbnail != null && !thumbnail.isEmpty()) {
            content.updateThumbnail(thumbnailStorage.upload(thumbnail));
        }
        Content saved = contentRepository.save(content);
        eventPublisher.publishEvent(new ContentSearchSyncEvent(saved.getId()));
        return ContentDto.from(saved);
    }

    @Override
    public ContentDto get(UUID contentId) {
        Content content = findOrThrow(contentId);
        long liveWatcherCount = watchingSessionSnapshotRepository.countByContentId(contentId, null, Instant.now());
        return ContentDto.from(content, liveWatcherCount);
    }

    // 정렬/커서 계산(fetchPage)과 표시용 실시간 시청자 수 집계(countGroupedByContentIds)가
    // 서로 다른 쿼리라, 기본 READ COMMITTED(문장별 스냅샷)에서는 그 사이 다른 트랜잭션이 커밋한
    // 시청 세션 변경을 서로 다르게 볼 수 있다. REPEATABLE_READ로 트랜잭션 시작 시점 스냅샷을
    // 공유시켜 두 쿼리가 같은 시청자 수 기준으로 동작하도록 한다.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CursorResponse<ContentDto> getList(
            String typeEqual, String keywordLike, List<String> tagsIn,
            String cursor, UUID idAfter, int limit, String sortBy, String sortDirection) {

        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (!SORT_CREATED_AT.equals(sortBy) && !SORT_WATCHER_COUNT.equals(sortBy) && !SORT_AVERAGE_RATING.equals(sortBy)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        String typeStr = typeEqual != null ? ContentType.fromApiValue(typeEqual).name() : null;
        String escapedKeyword = keywordLike != null ? escapeLikePattern(keywordLike) : null;
        List<String> normalizedTags = tagsIn == null ? List.of()
                : tagsIn.stream().map(Content::normalize).distinct().toList();
        int tagCount = normalizedTags.size();
        List<String> tagsForQuery = normalizedTags.isEmpty() ? List.of("") : normalizedTags;

        Instant now = Instant.now();
        int fetchSize = limit + 1;
        List<Content> rows = fetchPage(
                typeStr, escapedKeyword, tagsForQuery, tagCount, cursor, idAfter, fetchSize, sortBy, sortDirection, now);

        boolean hasNext = rows.size() == fetchSize;
        List<Content> page = hasNext ? rows.subList(0, limit) : rows;

        // sortBy와 무관하게 목록에 노출되는 watcherCount는 항상 실시간 집계값이어야 하므로,
        // createdAt/averageRating 정렬로 조회했을 때도 이 맵을 채워 ContentDto에 반영함.
        Map<UUID, Long> liveWatcherCounts = buildLiveWatcherCounts(page, now);

        String nextCursor = null;
        UUID nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            Content last = page.get(page.size() - 1);
            nextCursor = buildNextCursor(last, sortBy, sortDirection, liveWatcherCounts);
            nextIdAfter = last.getId();
        }

        List<ContentDto> data = page.stream()
                .map(content -> ContentDto.from(content, liveWatcherCounts.getOrDefault(content.getId(), 0L)))
                .toList();
        long total = contentRepository.countByFilter(typeStr, escapedKeyword, tagsForQuery, tagCount);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, total, sortBy, sortDirection);
    }

    @Override
    @Transactional
    public ContentDto update(UUID contentId, ContentUpdateRequest request, MultipartFile thumbnail) {
        Content content = findOrThrow(contentId);
        Set<String> tags = request.tags() != null ? new HashSet<>(request.tags()) : null;
        content.update(request.title(), request.description(), tags);
        if (thumbnail != null && !thumbnail.isEmpty()) {
            content.updateThumbnail(thumbnailStorage.upload(thumbnail));
        }
        Content saved = contentRepository.save(content);
        eventPublisher.publishEvent(new ContentSearchSyncEvent(saved.getId()));
        return ContentDto.from(saved);
    }

    @Override
    @Transactional
    public void delete(UUID contentId) {
        Content content = findOrThrow(contentId);
        contentRepository.delete(content);
        eventPublisher.publishEvent(new ContentSearchDeleteEvent(contentId));
    }

    private Content findOrThrow(UUID contentId) {
        return contentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private List<Content> fetchPage(
            String typeStr, String keywordLike, List<String> tags, int tagCount,
            String cursor, UUID idAfter, int limit, String sortBy, String sortDirection, Instant now) {

        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        boolean isAsc = DIRECTION_ASC.equalsIgnoreCase(sortDirection);
        String idAfterStr = idAfter != null ? idAfter.toString() : null;

        try {
            if (SORT_WATCHER_COUNT.equals(sortBy)) {
                if (isAsc) {
                    Long cursorCount = (cursor != null) ? CursorUtils.decodeAsLong(cursor) : null;
                    return contentRepository.findByWatcherCountAsc(
                            typeStr, keywordLike, tags, tagCount, cursorCount, idAfterStr, now, limit);
                }
                Long cursorWatcherCount = null;
                Long cursorReviewCount = null;
                if (cursor != null) {
                    CursorUtils.LongPair pair = CursorUtils.decodeAsLongPair(cursor);
                    cursorWatcherCount = pair.first();
                    cursorReviewCount = pair.second();
                }
                return contentRepository.findByWatcherCountDesc(
                        typeStr, keywordLike, tags, tagCount,
                        cursorWatcherCount, cursorReviewCount, idAfterStr, now, limit);
            }

            if (SORT_AVERAGE_RATING.equals(sortBy)) {
                BigDecimal cursorRating = (cursor != null) ? CursorUtils.decodeAsBigDecimal(cursor) : null;
                return isAsc
                        ? contentRepository.findByAverageRatingAsc(
                                typeStr, keywordLike, tags, tagCount, cursorRating, idAfterStr, limit)
                        : contentRepository.findByAverageRatingDesc(
                                typeStr, keywordLike, tags, tagCount, cursorRating, idAfterStr, limit);
            }

            Instant cursorTime = (cursor != null) ? CursorUtils.decodeAsInstant(cursor) : null;
            return isAsc
                    ? contentRepository.findByCreatedAtAsc(
                            typeStr, keywordLike, tags, tagCount, cursorTime, idAfterStr, limit)
                    : contentRepository.findByCreatedAtDesc(
                            typeStr, keywordLike, tags, tagCount, cursorTime, idAfterStr, limit);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private String buildNextCursor(Content last, String sortBy, String sortDirection, Map<UUID, Long> liveWatcherCounts) {
        if (SORT_WATCHER_COUNT.equals(sortBy)) {
            boolean isAsc = DIRECTION_ASC.equalsIgnoreCase(sortDirection);
            long watcherCount = liveWatcherCounts.getOrDefault(last.getId(), 0L);
            return isAsc
                    ? CursorUtils.encodeLong(watcherCount)
                    : CursorUtils.encodeLongPair(watcherCount, last.getReviewCount());
        }
        if (SORT_AVERAGE_RATING.equals(sortBy)) {
            return CursorUtils.encodeBigDecimal(last.getAverageRating());
        }
        return CursorUtils.encodeInstant(last.getCreatedAt());
    }

    private Map<UUID, Long> buildLiveWatcherCounts(List<Content> page, Instant now) {
        if (page.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = page.stream().map(Content::getId).toList();
        return watchingSessionSnapshotRepository.countGroupedByContentIds(ids, now).stream()
                .collect(Collectors.toMap(ContentWatcherCountView::getContentId, ContentWatcherCountView::getWatcherCount));
    }
}
