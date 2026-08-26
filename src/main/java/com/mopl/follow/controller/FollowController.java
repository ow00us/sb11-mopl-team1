package com.mopl.follow.controller;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowRecommendationItemDto;
import com.mopl.follow.dto.FollowUserItemDto;
import com.mopl.follow.dto.FollowerCountDto;
import com.mopl.follow.dto.FollowRequest;
import com.mopl.follow.service.FollowResult;
import com.mopl.follow.service.FollowService;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "기존 팔로우 반환"),
        @ApiResponse(responseCode = "201", description = "팔로우 생성 성공")
    })
    public ResponseEntity<FollowDto> follow(@Valid @RequestBody FollowRequest request) {
        UUID followerId = resolveUserId();
        FollowResult result = followService.follow(followerId, request.followeeId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.dto());
    }

    @DeleteMapping("/{followId}")
    @ApiResponse(responseCode = "204", description = "팔로우 취소 성공")
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

    @GetMapping("/followers")
    public CursorResponse<FollowUserItemDto> getFollowers(
            @RequestParam UUID followeeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "followedAt") @Pattern(regexp = "followedAt") String sortBy,
            @RequestParam(defaultValue = "DESCENDING") @Pattern(regexp = "DESCENDING") String sortDirection) {
        return followService.getFollowers(followeeId, cursor, idAfter, limit, sortBy, sortDirection);
    }

    @GetMapping("/recommendations")
    public CursorResponse<FollowRecommendationItemDto> getRecommendations(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "commonFollowingCount") @Pattern(regexp = "commonFollowingCount") String sortBy,
            @RequestParam(defaultValue = "DESCENDING") @Pattern(regexp = "DESCENDING") String sortDirection) {
        UUID requesterId = resolveUserId();
        return followService.getRecommendations(requesterId, cursor, idAfter, limit, sortBy, sortDirection);
    }

    @GetMapping("/followings")
    public CursorResponse<FollowUserItemDto> getFollowings(
            @RequestParam UUID followerId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "followedAt") @Pattern(regexp = "followedAt") String sortBy,
            @RequestParam(defaultValue = "DESCENDING") @Pattern(regexp = "DESCENDING") String sortDirection) {
        return followService.getFollowings(followerId, cursor, idAfter, limit, sortBy, sortDirection);
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
