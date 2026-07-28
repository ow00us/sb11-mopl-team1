package com.mopl.follow.controller;

import com.mopl.follow.dto.FollowDto;
import com.mopl.follow.dto.FollowRequest;
import com.mopl.follow.service.FollowService;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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
    public long countFollowers(@RequestParam UUID followeeId) {
        return followService.countFollowers(followeeId);
    }

    @GetMapping("/followed-by-me")
    public FollowDto getFollowedByMe(@RequestParam UUID followeeId) {
        UUID followerId = resolveUserId();
        return followService.getFollowedByMe(followerId, followeeId);
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