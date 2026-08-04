package com.mopl.review.controller;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewDto;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.service.ReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 리뷰 CRUD REST 엔드포인트를 제공하는 컨트롤러입니다. */
@Validated
@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /** 리뷰를 생성합니다. 인증된 사용자만 호출 가능합니다. */
    @PostMapping
    public ResponseEntity<ReviewDto> create(@Valid @RequestBody ReviewCreateRequest request) {
        UUID authorId = resolveUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(reviewService.create(request, authorId));
    }

    /** 커서 페이지네이션으로 리뷰 목록을 조회합니다. 공개 API로, 인증이 필요 없습니다. */
    @GetMapping
    public CursorResponse<ReviewDto> getList(
            @RequestParam(required = false) UUID contentId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam @Pattern(regexp = "ASCENDING|DESCENDING") String sortDirection,
            @RequestParam @Pattern(regexp = "createdAt|rating") String sortBy) {
        return reviewService.getList(contentId, cursor, idAfter, limit, sortBy, sortDirection);
    }

    /** 리뷰를 수정합니다. 작성자만 호출 가능합니다. */
    @PatchMapping("/{reviewId}")
    public ReviewDto update(@PathVariable UUID reviewId, @Valid @RequestBody ReviewUpdateRequest request) {
        UUID requesterId = resolveUserId();
        return reviewService.update(reviewId, request, requesterId);
    }

    /** 리뷰를 삭제합니다. 작성자만 호출 가능합니다. */
    @DeleteMapping("/{reviewId}")
    public ResponseEntity<Void> delete(@PathVariable UUID reviewId) {
        UUID requesterId = resolveUserId();
        reviewService.delete(reviewId, requesterId);
        return ResponseEntity.noContent().build();
    }

    /** SecurityContext에서 현재 사용자 ID를 추출합니다. 미인증 시 UNAUTHORIZED를 발생시킵니다. */
    private UUID resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}