package com.mopl.playlist.controller;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.service.PlaylistService;
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
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;

    @PostMapping
    public ResponseEntity<PlaylistDto> create(
            @Valid @RequestBody PlaylistCreateRequest request) {
        UUID ownerId = resolveUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(playlistService.create(request, ownerId));
    }

    @GetMapping
    public CursorResponse<PlaylistDto> getList(
            @RequestParam(required = false) String keywordLike,
            @RequestParam(required = false) UUID ownerIdEqual,
            @RequestParam(required = false) UUID subscriberIdEqual,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam @Pattern(regexp = "updatedAt|subscriberCount") String sortBy,
            @RequestParam @Pattern(regexp = "ASCENDING|DESCENDING") String sortDirection) {
        UUID requesterId = resolveUserIdOptional();
        return playlistService.getList(
                keywordLike, ownerIdEqual, subscriberIdEqual, cursor, idAfter,
                limit, sortBy, sortDirection, requesterId);
    }

    @GetMapping("/{playlistId}")
    public PlaylistDto get(@PathVariable UUID playlistId) {
        UUID requesterId = resolveUserIdOptional();
        return playlistService.get(playlistId, requesterId);
    }

    @PatchMapping("/{playlistId}")
    public PlaylistDto update(
            @PathVariable UUID playlistId,
            @Valid @RequestBody PlaylistUpdateRequest request) {
        UUID requesterId = resolveUserId();
        return playlistService.update(playlistId, request, requesterId);
    }

    @DeleteMapping("/{playlistId}")
    public ResponseEntity<Void> delete(@PathVariable UUID playlistId) {
        UUID requesterId = resolveUserId();
        playlistService.delete(playlistId, requesterId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{playlistId}/subscription")
    public ResponseEntity<Void> subscribe(@PathVariable UUID playlistId) {
        UUID subscriberId = resolveUserId();
        playlistService.subscribe(playlistId, subscriberId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{playlistId}/subscription")
    public ResponseEntity<Void> unsubscribe(@PathVariable UUID playlistId) {
        UUID subscriberId = resolveUserId();
        playlistService.unsubscribe(playlistId, subscriberId);
        return ResponseEntity.noContent().build();
    }

    /** 인증된 사용자 ID를 추출합니다. 미인증 시 UNAUTHORIZED 를 발생시킵니다. */
    private UUID resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return UUID.fromString(auth.getName());
    }

    /** 인증된 사용자 ID를 추출합니다. 미인증 시 null 을 반환합니다. */
    private UUID resolveUserIdOptional() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return UUID.fromString(auth.getName());
    }
}