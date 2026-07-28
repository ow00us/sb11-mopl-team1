package com.mopl.watchingsession.controller;

import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class WatchingSessionController {

    private final WatchingSessionService watchingSessionService;

    @PostMapping("/contents/{contentId}/watching-sessions")
    public ResponseEntity<WatchingSessionDto> start(
        @PathVariable UUID contentId,
        Authentication authentication
    ) {
        UUID watcherId = currentUserId(authentication);
        WatchingSessionDto response = watchingSessionService.start(watcherId, contentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/watching-sessions/me")
    public ResponseEntity<Void> end(Authentication authentication) {
        UUID watcherId = currentUserId(authentication);
        watchingSessionService.end(watcherId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users/{watcherId}/watching-sessions")
    public ResponseEntity<WatchingSessionDto> get(@PathVariable UUID watcherId) {
        return watchingSessionService.get(watcherId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // TODO: JWT 구현 완료 후 Authentication.getName()이 사용자 UUID 문자열이 맞는지 확인 및 교체
    private UUID currentUserId(Authentication authentication) {
        return UUID.fromString(authentication.getName());
    }

}
