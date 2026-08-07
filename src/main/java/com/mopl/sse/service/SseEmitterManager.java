package com.mopl.sse.service;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class SseEmitterManager {

    private static final long TIMEOUT_MILLIS =
        30 * 60 * 1000L;

    private final Map<
        UUID,
        Map<UUID, SseEmitter>
        > emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(
        UUID userId
    ) {
        UUID emitterId = UUID.randomUUID();

        SseEmitter emitter =
            new SseEmitter(
                TIMEOUT_MILLIS
            );

        emitters.compute(
            userId,
            (id, userEmitters) -> {
                Map<UUID, SseEmitter> connections =
                    userEmitters == null
                        ? new ConcurrentHashMap<>()
                        : userEmitters;

                connections.put(
                    emitterId,
                    emitter
                );

                return connections;
            }
        );

        emitter.onCompletion(
            () -> remove(
                userId,
                emitterId,
                emitter
            )
        );

        emitter.onTimeout(() -> {
            remove(
                userId,
                emitterId,
                emitter
            );

            emitter.complete();
        });

        emitter.onError(
            exception ->
                remove(
                    userId,
                    emitterId,
                    emitter
                )
        );

        sendConnectionComment(
            userId,
            emitterId,
            emitter
        );

        return emitter;
    }

    public void send(
        UUID userId,
        UUID eventId,
        String eventName,
        Object data
    ) {
        Map<UUID, SseEmitter> userEmitters =
            emitters.get(userId);

        if (userEmitters == null) {
            return;
        }

        userEmitters.forEach(
            (emitterId, emitter) ->
                sendEvent(
                    userId,
                    emitterId,
                    emitter,
                    eventId,
                    eventName,
                    data
                )
        );
    }

    @Scheduled(
        fixedDelayString =
            "${mopl.sse.heartbeat-interval-millis:20000}"
    )
    public void sendHeartbeat() {
        emitters.forEach(
            (userId, userEmitters) ->
                userEmitters.forEach(
                    (emitterId, emitter) ->
                        sendHeartbeatComment(
                            userId,
                            emitterId,
                            emitter
                        )
                )
        );
    }

    private void sendEvent(
        UUID userId,
        UUID emitterId,
        SseEmitter emitter,
        UUID eventId,
        String eventName,
        Object data
    ) {
        try {
            emitter.send(
                SseEmitter.event()
                    .id(eventId.toString())
                    .name(eventName)
                    .data(data)
            );
        } catch (
            IOException | IllegalStateException exception
        ) {
            handleSendFailure(
                userId,
                emitterId,
                emitter,
                exception,
                eventName
            );
        }
    }

    private void sendConnectionComment(
        UUID userId,
        UUID emitterId,
        SseEmitter emitter
    ) {
        try {
            emitter.send(
                SseEmitter.event()
                    .comment("connected")
            );
        } catch (
            IOException | IllegalStateException exception
        ) {
            remove(
                userId,
                emitterId,
                emitter
            );

            completeWithError(
                emitter,
                exception
            );

            throw new BusinessException(
                ErrorCode.INTERNAL_ERROR
            );
        }
    }

    private void sendHeartbeatComment(
        UUID userId,
        UUID emitterId,
        SseEmitter emitter
    ) {
        try {
            emitter.send(
                SseEmitter.event()
                    .comment("heartbeat")
            );
        } catch (
            IOException | IllegalStateException exception
        ) {
            handleSendFailure(
                userId,
                emitterId,
                emitter,
                exception,
                "heartbeat"
            );
        }
    }

    private void handleSendFailure(
        UUID userId,
        UUID emitterId,
        SseEmitter emitter,
        Exception exception,
        String eventName
    ) {
        remove(
            userId,
            emitterId,
            emitter
        );

        completeWithError(
            emitter,
            exception
        );

        log.warn(
            "SSE 이벤트 전송 실패 - userId: {}, "
                + "emitterId: {}, eventName: {}",
            userId,
            emitterId,
            eventName,
            exception
        );
    }

    private void completeWithError(
        SseEmitter emitter,
        Exception exception
    ) {
        try {
            emitter.completeWithError(
                exception
            );
        } catch (IllegalStateException ignored) {
            log.debug(
                "이미 종료된 SSE 연결입니다."
            );
        }
    }

    private void remove(
        UUID userId,
        UUID emitterId,
        SseEmitter emitter
    ) {
        emitters.computeIfPresent(
            userId,
            (id, userEmitters) -> {
                userEmitters.remove(
                    emitterId,
                    emitter
                );

                return userEmitters.isEmpty()
                    ? null
                    : userEmitters;
            }
        );
    }
}
