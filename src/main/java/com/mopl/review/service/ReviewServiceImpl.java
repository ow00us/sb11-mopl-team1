package com.mopl.review.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewDto;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.entity.Review;
import com.mopl.review.repository.ReviewRepository;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewServiceImpl implements ReviewService {

    private static final String SORT_CREATED_AT = "createdAt";
    private static final String SORT_RATING = "rating";
    private static final String DIRECTION_ASC = "ASCENDING";
    private static final String DIRECTION_DESC = "DESCENDING";
    private static final String PG_UNIQUE_VIOLATION_SQLSTATE = "23505";
    private static final String UNKNOWN_AUTHOR_NAME = "알 수 없는 사용자";

    private final ReviewRepository reviewRepository;
    private final ContentRepository contentRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public ReviewDto create(ReviewCreateRequest request, UUID authorId) {
        contentRepository.findById(request.contentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));

        if (reviewRepository.existsByAuthorIdAndContentId(authorId, request.contentId())) {
            throw new BusinessException(ErrorCode.REVIEW_DUPLICATE);
        }

        Review review = Review.builder()
                .authorId(authorId)
                .contentId(request.contentId())
                .text(request.text())
                .rating(request.rating())
                .build();

        try {
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKeyViolation(e)) {
                throw new BusinessException(ErrorCode.REVIEW_DUPLICATE);
            }
            throw e;
        }

        refreshContentAggregate(request.contentId());

        UserSummary author = toUserSummary(authorId);
        return ReviewDto.from(review, author);
    }

    @Override
    @Transactional
    public ReviewDto update(UUID reviewId, ReviewUpdateRequest request, UUID requesterId) {
        Review review = findOrThrow(reviewId);
        verifyAuthor(review, requesterId);
        review.update(request.text(), request.rating());
        reviewRepository.saveAndFlush(review);

        refreshContentAggregate(review.getContentId());

        UserSummary author = toUserSummary(review.getAuthorId());
        return ReviewDto.from(review, author);
    }

    @Override
    @Transactional
    public void delete(UUID reviewId, UUID requesterId) {
        Review review = findOrThrow(reviewId);
        verifyAuthor(review, requesterId);
        UUID contentId = review.getContentId();
        reviewRepository.delete(review);
        reviewRepository.flush();

        refreshContentAggregate(contentId);
    }

    @Override
    public CursorResponse<ReviewDto> getList(
            UUID contentId, String cursor, UUID idAfter, int limit, String sortBy, String sortDirection) {

        if (limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (!SORT_CREATED_AT.equals(sortBy) && !SORT_RATING.equals(sortBy)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        if (!DIRECTION_ASC.equalsIgnoreCase(sortDirection) && !DIRECTION_DESC.equalsIgnoreCase(sortDirection)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        int fetchSize = limit + 1;
        List<Review> rows = fetchPage(contentId, cursor, idAfter, fetchSize, sortBy, sortDirection);

        boolean hasNext = rows.size() == fetchSize;
        List<Review> page = hasNext ? rows.subList(0, limit) : rows;

        String nextCursor = null;
        UUID nextIdAfter = null;
        if (hasNext && !page.isEmpty()) {
            Review last = page.get(page.size() - 1);
            nextCursor = buildNextCursor(last, sortBy);
            nextIdAfter = last.getId();
        }

        List<ReviewDto> data = toDtos(page);
        long total = reviewRepository.countByFilter(contentId);

        return CursorResponse.of(data, nextCursor, nextIdAfter, hasNext, total, sortBy, sortDirection);
    }

    @Override
    public ReviewDto getMyReview(UUID contentId, UUID authorId) {
        contentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND));

        Review review = reviewRepository.findByAuthorIdAndContentId(authorId, contentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));

        UserSummary author = toUserSummary(authorId);
        return ReviewDto.from(review, author);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────

    private Review findOrThrow(UUID reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND));
    }

    private void verifyAuthor(Review review, UUID requesterId) {
        if (!review.getAuthorId().equals(requesterId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
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

    // 리뷰 변경사항이 이미 flush 되어 있어야 이 원자적 갱신 쿼리에 반영된다.
    // create/update/delete 각각 saveAndFlush/flush 호출 이후에만 이 메서드를 호출할 것.
    // 콘텐츠 행 락을 먼저 선점(findByIdForUpdateWithLockTimeout)한 뒤 별도 문으로 집계를 갱신해야
    // 락 대기 중 커밋된 다른 리뷰까지 정확히 반영된다. ContentRepository.findByIdForUpdateWithLockTimeout 참고.
    private void refreshContentAggregate(UUID contentId) {
        contentRepository.findByIdForUpdateWithLockTimeout(contentId)
                .ifPresent(content -> contentRepository.refreshReviewAggregate(contentId));
    }

    private UserSummary toUserSummary(UUID userId) {
        return userRepository.findById(userId)
                .map(user -> new UserSummary(user.getId(), user.getName(), user.getProfileImageUrl()))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // getList 페이지 안의 작성자들을 한 번에 배치 조회해 N+1을 방지한다.
    private List<ReviewDto> toDtos(List<Review> reviews) {
        List<UUID> authorIds = reviews.stream().map(Review::getAuthorId).distinct().toList();
        Map<UUID, User> usersById = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, user -> user));

        return reviews.stream()
                .map(review -> {
                    User author = usersById.get(review.getAuthorId());
                    UserSummary summary = (author != null)
                            ? toUserSummary(author)
                            : new UserSummary(review.getAuthorId(), UNKNOWN_AUTHOR_NAME, null);
                    return ReviewDto.from(review, summary);
                })
                .toList();
    }

    private UserSummary toUserSummary(User user) {
        return new UserSummary(user.getId(), user.getName(), user.getProfileImageUrl());
    }

    private List<Review> fetchPage(
            UUID contentId, String cursor, UUID idAfter, int limit, String sortBy, String sortDirection) {

        if ((cursor != null) != (idAfter != null)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        boolean isAsc = DIRECTION_ASC.equalsIgnoreCase(sortDirection);

        try {
            if (SORT_RATING.equals(sortBy)) {
                BigDecimal cursorRating = (cursor != null) ? CursorUtils.decodeAsBigDecimal(cursor) : null;
                return isAsc
                        ? reviewRepository.findByRatingAsc(contentId, cursorRating, idAfter, limit)
                        : reviewRepository.findByRatingDesc(contentId, cursorRating, idAfter, limit);
            }

            Instant cursorTime = (cursor != null) ? CursorUtils.decodeAsInstant(cursor) : null;
            return isAsc
                    ? reviewRepository.findByCreatedAtAsc(contentId, cursorTime, idAfter, limit)
                    : reviewRepository.findByCreatedAtDesc(contentId, cursorTime, idAfter, limit);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private String buildNextCursor(Review last, String sortBy) {
        if (SORT_RATING.equals(sortBy)) {
            return CursorUtils.encodeBigDecimal(last.getRating());
        }
        return CursorUtils.encodeInstant(last.getCreatedAt());
    }
}