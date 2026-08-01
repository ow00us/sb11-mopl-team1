package com.mopl.follow.controller;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowUserItemDto;
import com.mopl.follow.dto.FollowerCountDto;
import com.mopl.follow.dto.FollowRequest;
import com.mopl.follow.service.FollowService;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/follows")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @PostMapping
    public ResponseEntity<FollowDto> follow(@Valid @RequestBody FollowRequest request) {
        UUID followerId = resolveUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(followService.follow(followerId, request.followeeId()));
    }

    @DeleteMapping("/{followId}")
    public ResponseEntity<Void> unfollow(@PathVariable UUID followId) {
        UUID requesterId = resolveUserId();
        followService.unfollow(followId, requesterId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/count")
    public FollowerCountDto countFollowers(@RequestParam UUID followeeId) {
        return new FollowerCountDto(followService.countFollowers(followeeId));
    }

    @GetMapping("/followed-by-me")
    public FollowDto getFollowedByMe(@RequestParam UUID followeeId) {
        UUID followerId = resolveUserId();
        return followService.getFollowedByMe(followerId, followeeId);
    }

    // Red 스텁: 파라미터 검증만 동작하고 실제 위임은 Green 에서 붙인다.
    @GetMapping("/followers")
    public CursorResponse<FollowUserItemDto> getFollowers(
            @RequestParam UUID followeeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "followedAt") @Pattern(regexp = "followedAt") String sortBy,
            @RequestParam(defaultValue = "DESCENDING") @Pattern(regexp = "ASCENDING|DESCENDING") String sortDirection) {
        throw new UnsupportedOperationException("Green 단계에서 구현");
    }

    @GetMapping("/followings")
    public CursorResponse<FollowUserItemDto> getFollowings(
            @RequestParam UUID followerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "followedAt") @Pattern(regexp = "followedAt") String sortBy,
            @RequestParam(defaultValue = "DESCENDING") @Pattern(regexp = "ASCENDING|DESCENDING") String sortDirection) {
        throw new UnsupportedOperationException("Green 단계에서 구현");
    }

    private UUID resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}