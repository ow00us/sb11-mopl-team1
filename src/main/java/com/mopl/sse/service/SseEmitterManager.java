package com.mopl.sse.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SseEmitterManager {

    private static final long TIMEOUT_MILLIS =
        30 * 60 * 1000L;

    private final Map<UUID, SseEmitter> emitters =
        new ConcurrentHashMap<>();

    public SseEmitter subscribe(
        UUID userId
    ) {
        SseEmitter emitter =
            new SseEmitter(
                TIMEOUT_MILLIS
            );

        SseEmitter previousEmitter =
            emitters.put(
                userId,
                emitter
            );

        if (previousEmitter != null) {
            previousEmitter.complete();
        }

        emitter.onCompletion(
            () -> remove(
                userId,
                emitter
            )
        );

        emitter.onTimeout(() -> {
            remove(
                userId,
                emitter
            );

            emitter.complete();
        });

        emitter.onError(
            exception ->
                remove(
                    userId,
                    emitter
                )
        );

        sendConnectionComment(
            userId,
            emitter
        );

        return emitter;
    }

    private void sendConnectionComment(
        UUID userId,
        SseEmitter emitter
    ) {
        try {
            emitter.send(
                SseEmitter.event()
                    .comment("connected")
            );
        } catch (IOException exception) {
            remove(
                userId,
                emitter
            );

            throw new BusinessException(
                ErrorCode.INTERNAL_ERROR
            );
        }
    }

    private void remove(
        UUID userId,
        SseEmitter emitter
    ) {
        emitters.remove(
            userId,
            emitter
        );
    }
}
