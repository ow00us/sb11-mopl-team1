package com.mopl.sse.controller;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.sse.service.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/sse")
@RequiredArgsConstructor
public class SseController {

    private final SseEmitterManager sseEmitterManager;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
        @RequestHeader(
            name = "Last-Event-ID",
            required = false
        )
        String lastEventId,
        Principal principal
    ) {
        UUID userId = getUserId(principal);

        return sseEmitterManager.subscribe(
            userId
        );
    }

    private UUID getUserId(
        Principal principal
    ) {
        if (principal == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        String principalName = principal.getName();

        if (principalName == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        try {
            return UUID.fromString(principalName);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }
    }
}
