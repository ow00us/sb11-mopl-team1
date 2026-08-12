package com.mopl.review.service;

import com.mopl.global.common.CursorResponse;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewDto;
import com.mopl.review.dto.ReviewUpdateRequest;
import java.util.UUID;

public interface ReviewService {

    ReviewDto create(ReviewCreateRequest request, UUID authorId);

    ReviewDto update(UUID reviewId, ReviewUpdateRequest request, UUID requesterId);

    void delete(UUID reviewId, UUID requesterId);

    CursorResponse<ReviewDto> getList(
            UUID contentId, String cursor, UUID idAfter, int limit, String sortBy, String sortDirection);

    ReviewDto getMyReview(UUID contentId, UUID authorId);
}