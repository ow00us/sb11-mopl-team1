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

/** 플레이리스트 CRUD REST 엔드포인트를 제공하는 컨트롤러입니다. */
@Validated
@RestController
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;

    /** 플레이리스트를 생성합니다. 인증된 사용자만 호출 가능합니다. */
    @PostMapping
    public ResponseEntity<PlaylistDto> create(
            @Valid @RequestBody PlaylistCreateRequest request) {
        UUID ownerId = resolveUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(playlistService.create(request, ownerId));
    }

    /** 커서 페이지네이션으로 플레이리스트 목록을 조회합니다. */
    @GetMapping
    public CursorResponse<PlaylistDto> getList(
            @RequestParam(required = false) String keywordLike,
            @RequestParam(required = false) UUID ownerIdEqual,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam @Min(1) @Max(100) int limit,
            @RequestParam @Pattern(regexp = "updatedAt|subscriberCount") String sortBy,
            @RequestParam @Pattern(regexp = "ASCENDING|DESCENDING") String sortDirection) {
        return playlistService.getList(
                keywordLike, ownerIdEqual, cursor, idAfter, limit, sortBy, sortDirection);
    }

    /** 플레이리스트를 단건 조회합니다. */
    @GetMapping("/{playlistId}")
    public PlaylistDto get(@PathVariable UUID playlistId) {
        return playlistService.get(playlistId);
    }

    /** 플레이리스트를 수정합니다. 소유자만 호출 가능합니다. */
    @PatchMapping("/{playlistId}")
    public PlaylistDto update(
            @PathVariable UUID playlistId,
            @Valid @RequestBody PlaylistUpdateRequest request) {
        UUID requesterId = resolveUserId();
        return playlistService.update(playlistId, request, requesterId);
    }

    /** 플레이리스트를 삭제합니다. 소유자만 호출 가능합니다. */
    @DeleteMapping("/{playlistId}")
    public ResponseEntity<Void> delete(@PathVariable UUID playlistId) {
        UUID requesterId = resolveUserId();
        playlistService.delete(playlistId, requesterId);
        return ResponseEntity.noContent().build();
    }

    /** SecurityContextHolder에서 현재 사용자 ID를 추출합니다. 미인증 시 UNAUTHORIZED를 발생시킵니다. */
    private UUID resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return UUID.fromString(auth.getName());
    }
}
