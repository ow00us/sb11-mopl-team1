package com.mopl.playlist.controller;

import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.playlist.dto.PlaylistCreateRequest;
import com.mopl.playlist.dto.PlaylistDto;
import com.mopl.playlist.dto.PlaylistUpdateRequest;
import com.mopl.playlist.service.PlaylistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private final PlaylistService playlistService;

    @PostMapping
    public ResponseEntity<PlaylistDto> create(
            @Valid @RequestBody PlaylistCreateRequest request,
            Authentication auth) {
        throw new UnsupportedOperationException("미구현");
    }

    @GetMapping
    public CursorResponse<PlaylistDto> getList(
            @RequestParam(required = false) String keywordLike,
            @RequestParam(required = false) UUID ownerIdEqual,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) UUID idAfter,
            @RequestParam int limit,
            @RequestParam String sortBy,
            @RequestParam String sortDirection) {
        throw new UnsupportedOperationException("미구현");
    }

    @GetMapping("/{playlistId}")
    public PlaylistDto get(@PathVariable UUID playlistId) {
        throw new UnsupportedOperationException("미구현");
    }

    @PatchMapping("/{playlistId}")
    public PlaylistDto update(
            @PathVariable UUID playlistId,
            @Valid @RequestBody PlaylistUpdateRequest request,
            Authentication auth) {
        throw new UnsupportedOperationException("미구현");
    }

    @DeleteMapping("/{playlistId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID playlistId,
            Authentication auth) {
        throw new UnsupportedOperationException("미구현");
    }

    private UUID resolveUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getPrincipal())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return UUID.fromString(auth.getName());
    }
}