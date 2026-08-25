package com.mopl.content.service;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.search.ContentDocument;
import com.mopl.content.search.ContentSearchDeleteEvent;
import com.mopl.content.search.ContentSearchExecutor;
import com.mopl.content.search.ContentSearchSyncEvent;
import com.mopl.content.storage.ThumbnailStorage;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
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
    private final ContentSearchExecutor contentSearchExecutor;
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

    @Override
    public CursorResponse<ContentDto> getList(
            String typeEqual, String keywordLike, List<String> tagsIn,
            String cursor, UUID idAfter, int limit, String sortBy, String sortDirection) {

        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (!SORT_CREATED_AT.equals(sortBy) && !SORT_WATCHER_COUNT.equals(sortBy) && !SORT_AVERAGE_RATING.equals(sortBy)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        List<String> normalizedTags = tagsIn == null ? List.of()
                : tagsIn.stream().map(Content::normalize).distinct().toList();

        int fetchSize = limit + 1;
        List<ContentDocument> rows = fetchPage(
                typeEqual, keywordLike, normalizedTags, cursor, idAfter, fetchSize, sortBy, sortDirection);
        long total = contentSearchExecutor.countByFilter(typeEqual, keywordLike, normalizedTags);

        boolean hasNext = rows.size() == fetchSize;
        List<ContentDocument> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor = null;
        UUID nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            ContentDocument last = page.get(page.size() - 1);
            nextCursor = buildNextCursor(last, sortBy, sortDirection);
            nextIdAfter = UUID.fromString(last.getId());
        }

        List<ContentDto> data = page.stream().map(ContentDto::from).toList();

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

    private List<ContentDocument> fetchPage(
            String typeEqual, String keywordLike, List<String> tags,
            String cursor, UUID idAfter, int limit, String sortBy, String sortDirection) {

        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        boolean isAsc = DIRECTION_ASC.equalsIgnoreCase(sortDirection);
        String idAfterStr = idAfter != null ? idAfter.toString() : null;

        try {
            if (SORT_WATCHER_COUNT.equals(sortBy)) {
                if (isAsc) {
                    Long cursorCount = (cursor != null) ? CursorUtils.decodeAsLong(cursor) : null;
                    return contentSearchExecutor.findByWatcherCountAsc(
                            typeEqual, keywordLike, tags, cursorCount, idAfterStr, limit);
                }
                Long cursorWatcherCount = null;
                Long cursorReviewCount = null;
                if (cursor != null) {
                    CursorUtils.LongPair pair = CursorUtils.decodeAsLongPair(cursor);
                    cursorWatcherCount = pair.first();
                    cursorReviewCount = pair.second();
                }
                return contentSearchExecutor.findByWatcherCountDesc(
                        typeEqual, keywordLike, tags, cursorWatcherCount, cursorReviewCount, idAfterStr, limit);
            }

            if (SORT_AVERAGE_RATING.equals(sortBy)) {
                BigDecimal cursorRating = (cursor != null) ? CursorUtils.decodeAsBigDecimal(cursor) : null;
                return isAsc
                        ? contentSearchExecutor.findByAverageRatingAsc(
                                typeEqual, keywordLike, tags, cursorRating, idAfterStr, limit)
                        : contentSearchExecutor.findByAverageRatingDesc(
                                typeEqual, keywordLike, tags, cursorRating, idAfterStr, limit);
            }

            Long cursorEpochMicros = (cursor != null) ? CursorUtils.decodeAsLong(cursor) : null;
            return isAsc
                    ? contentSearchExecutor.findByCreatedAtAsc(
                            typeEqual, keywordLike, tags, cursorEpochMicros, idAfterStr, limit)
                    : contentSearchExecutor.findByCreatedAtDesc(
                            typeEqual, keywordLike, tags, cursorEpochMicros, idAfterStr, limit);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private String buildNextCursor(ContentDocument last, String sortBy, String sortDirection) {
        if (SORT_WATCHER_COUNT.equals(sortBy)) {
            boolean isAsc = DIRECTION_ASC.equalsIgnoreCase(sortDirection);
            long watcherCount = last.getWatcherCount();
            return isAsc
                    ? CursorUtils.encodeLong(watcherCount)
                    : CursorUtils.encodeLongPair(watcherCount, last.getReviewCount());
        }
        if (SORT_AVERAGE_RATING.equals(sortBy)) {
            BigDecimal averageRating = BigDecimal.valueOf(last.getAverageRating()).setScale(1, RoundingMode.HALF_UP);
            return CursorUtils.encodeBigDecimal(averageRating);
        }
        // createdAt(밀리초)이 아니라 createdAtEpochMicros로 정렬·커서를 맞춘다 — Postgres
        // TIMESTAMP(6)의 마이크로초 정밀도가 createdAt(밀리초 매핑)에서는 잘려서, 같은 밀리초에
        // 여러 건이 생성돼도(배치 대량 삽입 등) 실제 생성 순서를 구분할 수 없기 때문이다.
        return CursorUtils.encodeLong(last.getCreatedAtEpochMicros());
    }
}
