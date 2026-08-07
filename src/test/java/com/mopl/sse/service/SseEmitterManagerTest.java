package com.mopl.sse.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseEmitterManagerTest {

    private static final UUID USER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID EVENT_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    @Test
    @DisplayName("SSE 연결을 생성하고 생명주기 콜백을 등록")
    void subscribe_createsEmitterAndRegistersCallbacks()
        throws Exception {

        try (
            MockedConstruction<SseEmitter> construction =
                mockConstruction(SseEmitter.class)
        ) {
            SseEmitterManager manager =
                new SseEmitterManager();

            // when
            SseEmitter result =
                manager.subscribe(USER_ID);

            // then
            SseEmitter emitter =
                construction.constructed().get(0);

            verify(emitter).onCompletion(
                any(Runnable.class)
            );

            verify(emitter).onTimeout(
                any(Runnable.class)
            );

            verify(emitter).onError(
                any()
            );

            verify(emitter).send(
                any(SseEmitter.SseEventBuilder.class)
            );

            assertThatCode(() -> {
                if (result != emitter) {
                    throw new IllegalStateException();
                }
            }).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("동일 사용자가 재연결하면 기존 연결을 종료하고 교체")
    void subscribe_existingUser_replacesEmitter()
        throws Exception {

        try (
            MockedConstruction<SseEmitter> construction =
                mockConstruction(SseEmitter.class)
        ) {
            SseEmitterManager manager =
                new SseEmitterManager();

            // when
            manager.subscribe(USER_ID);
            manager.subscribe(USER_ID);

            // then
            SseEmitter previousEmitter =
                construction.constructed().get(0);

            verify(previousEmitter).complete();
        }
    }

    @Test
    @DisplayName("연결된 사용자에게 SSE 이벤트를 전송")
    void send_connectedUser_sendsEvent()
        throws Exception {

        try (
            MockedConstruction<SseEmitter> construction =
                mockConstruction(SseEmitter.class)
        ) {
            SseEmitterManager manager =
                new SseEmitterManager();

            manager.subscribe(USER_ID);

            SseEmitter emitter =
                construction.constructed().get(0);

            // when
            manager.send(
                USER_ID,
                EVENT_ID,
                "notifications",
                "알림 내용"
            );

            // then
            // 최초 연결 comment 1회와 알림 이벤트 1회를 확인한다.
            verify(emitter, times(2)).send(
                any(SseEmitter.SseEventBuilder.class)
            );
        }
    }

    @Test
    @DisplayName("연결되지 않은 사용자에게 전송해도 예외가 발생하지 않음")
    void send_disconnectedUser_doesNothing() {
        SseEmitterManager manager =
            new SseEmitterManager();

        assertThatCode(() ->
            manager.send(
                USER_ID,
                EVENT_ID,
                "notifications",
                "알림 내용"
            )
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SSE 전송에 실패하면 연결을 제거")
    void send_failure_removesEmitter()
        throws Exception {

        try (
            MockedConstruction<SseEmitter> construction =
                mockConstruction(
                    SseEmitter.class,
                    (emitter, context) -> {
                        doNothing()
                            .doThrow(
                                new IOException(
                                    "SSE 전송 실패"
                                )
                            )
                            .when(emitter)
                            .send(
                                any(
                                    SseEmitter
                                        .SseEventBuilder
                                        .class
                                )
                            );
                    }
                )
        ) {
            SseEmitterManager manager =
                new SseEmitterManager();

            manager.subscribe(USER_ID);

            SseEmitter emitter =
                construction.constructed().get(0);

            // when
            manager.send(
                USER_ID,
                EVENT_ID,
                "notifications",
                "알림 내용"
            );

            // 제거된 연결이므로 두 번째 요청은 전송되지 않는다.
            manager.send(
                USER_ID,
                EVENT_ID,
                "notifications",
                "두 번째 알림"
            );

            // then
            verify(emitter, times(2)).send(
                any(SseEmitter.SseEventBuilder.class)
            );

            verify(emitter).completeWithError(
                any(IOException.class)
            );
        }
    }

    @Test
    @DisplayName("SSE 연결이 완료되면 저장된 연결을 제거")
    void completion_removesEmitter()
        throws Exception {

        try (
            MockedConstruction<SseEmitter> construction =
                mockConstruction(SseEmitter.class)
        ) {
            SseEmitterManager manager =
                new SseEmitterManager();

            manager.subscribe(USER_ID);

            SseEmitter emitter =
                construction.constructed().get(0);

            ArgumentCaptor<Runnable> callbackCaptor =
                ArgumentCaptor.forClass(Runnable.class);

            verify(emitter).onCompletion(
                callbackCaptor.capture()
            );

            // when
            callbackCaptor.getValue().run();

            manager.send(
                USER_ID,
                EVENT_ID,
                "notifications",
                "알림 내용"
            );

            // then
            // 연결 comment 한 번 외에 추가 전송이 없어야 한다.
            verify(emitter, times(1)).send(
                any(SseEmitter.SseEventBuilder.class)
            );
        }
    }
}
