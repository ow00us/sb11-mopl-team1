package com.mopl.content.service;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.content.storage.ThumbnailStorage;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentServiceImpl implements ContentService {

    private static final String SORT_WATCHER_COUNT = "watcherCount";
    private static final String SORT_AVERAGE_RATING = "averageRating";
    private static final String DIRECTION_ASC = "ASCENDING";

    private final ContentRepository contentRepository;
    private final ThumbnailStorage thumbnailStorage;

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
        content.updateThumbnail(thumbnailStorage.upload(thumbnail));
        return ContentDto.from(contentRepository.save(content));
    }

    @Override
    public ContentDto get(UUID contentId) {
        return ContentDto.from(findOrThrow(contentId));
    }

    @Override
    public CursorResponse<ContentDto> getList(
            String typeEqual, String keywordLike, List<String> tagsIn,
            String cursor, UUID idAfter, int limit, String sortBy, String sortDirection) {

        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        String typeStr = typeEqual != null ? ContentType.fromApiValue(typeEqual).name() : null;
        String escapedKeyword = keywordLike != null ? escapeLikePattern(keywordLike) : null;
        List<String> normalizedTags = tagsIn == null ? List.of()
                : tagsIn.stream().map(Content::normalize).distinct().toList();
        int tagCount = normalizedTags.size();
        List<String> tagsForQuery = normalizedTags.isEmpty() ? List.of("") : normalizedTags;

        int fetchSize = limit + 1;
        List<Content> rows = fetchPage(
                typeStr, escapedKeyword, tagsForQuery, tagCount, cursor, idAfter, fetchSize, sortBy, sortDirection);

        boolean hasNext = rows.size() == fetchSize;
        List<Content> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor = null;
        UUID nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            Content last = page.get(page.size() - 1);
            nextCursor = buildNextCursor(last, sortBy, sortDirection);
            nextIdAfter = last.getId();
        }

        List<ContentDto> data = page.stream().map(ContentDto::from).toList();
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
        return ContentDto.from(contentRepository.save(content));
    }

    @Override
    @Transactional
    public void delete(UUID contentId) {
        Content content = findOrThrow(contentId);
        contentRepository.delete(content);
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
                    return contentRepository.findByWatcherCountAsc(
                            typeStr, keywordLike, tags, tagCount, cursorCount, idAfterStr, limit);
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
                        cursorWatcherCount, cursorReviewCount, idAfterStr, limit);
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

    private String buildNextCursor(Content last, String sortBy, String sortDirection) {
        if (SORT_WATCHER_COUNT.equals(sortBy)) {
            boolean isAsc = DIRECTION_ASC.equalsIgnoreCase(sortDirection);
            return isAsc
                    ? CursorUtils.encodeLong(last.getWatcherCount())
                    : CursorUtils.encodeLongPair(last.getWatcherCount(), last.getReviewCount());
        }
        if (SORT_AVERAGE_RATING.equals(sortBy)) {
            return CursorUtils.encodeBigDecimal(last.getAverageRating());
        }
        return CursorUtils.encodeInstant(last.getCreatedAt());
    }
}