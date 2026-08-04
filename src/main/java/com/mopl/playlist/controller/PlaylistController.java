package com.mopl.playlist.controller;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.dto.SubscriberItemDto;
import com.mopl.playlist.service.PlaylistService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
    @ApiResponse(responseCode = "201", description = "플레이리스트 생성 성공")
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
    @ApiResponse(responseCode = "204", description = "플레이리스트 삭제 성공")
    public ResponseEntity<Void> delete(@PathVariable UUID playlistId) {
        UUID requesterId = resolveUserId();
        playlistService.delete(playlistId, requesterId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{playlistId}/subscription")
    @ApiResponse(responseCode = "204", description = "구독 완료")
    public ResponseEntity<Void> subscribe(@PathVariable UUID playlistId) {
        UUID subscriberId = resolveUserId();
        playlistService.subscribe(playlistId, subscriberId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{playlistId}/subscription")
    @ApiResponse(responseCode = "204", description = "구독 해지 완료")
    public ResponseEntity<Void> unsubscribe(@PathVariable UUID playlistId) {
        UUID subscriberId = resolveUserId();
        playlistService.unsubscribe(playlistId, subscriberId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{playlistId}/contents/{contentId}")
    @ApiResponse(responseCode = "204", description = "콘텐츠 추가 완료")
    public ResponseEntity<Void> addContent(
            @PathVariable UUID playlistId,
            @PathVariable UUID contentId) {
        UUID requesterId = resolveUserId();
        playlistService.addContent(playlistId, contentId, requesterId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{playlistId}/contents/{contentId}")
    @ApiResponse(responseCode = "204", description = "콘텐츠 제거 완료")
    public ResponseEntity<Void> removeContent(
            @PathVariable UUID playlistId,
            @PathVariable UUID contentId) {
        UUID requesterId = resolveUserId();
        playlistService.removeContent(playlistId, contentId, requesterId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{playlistId}/subscribers")
    public CursorResponse<SubscriberItemDto> getSubscribers(
            @PathVariable UUID playlistId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "subscribedAt") @Pattern(regexp = "subscribedAt") String sortBy,
            @RequestParam(defaultValue = "DESCENDING") @Pattern(regexp = "DESCENDING") String sortDirection) {
        // openapi 계약(BearerAuth) 을 준수하기 위해 SecurityConfig 와 무관하게 컨트롤러에서 인증을 강제한다.
        resolveUserId();
        return playlistService.getSubscribers(playlistId, cursor, idAfter, limit, sortBy, sortDirection);
    }

    /** 인증된 사용자 ID를 추출합니다. 미인증 시 UNAUTHORIZED 를 발생시킵니다. */
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

    /** 인증된 사용자 ID를 추출합니다. 미인증 시 null 을 반환합니다. */
    private UUID resolveUserIdOptional() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
