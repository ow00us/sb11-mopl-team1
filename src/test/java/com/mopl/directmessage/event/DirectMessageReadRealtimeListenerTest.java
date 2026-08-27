package com.mopl.directmessage.event;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.mopl.directmessage.dto.DirectMessageReadEvent;
import com.mopl.directmessage.websocket.DirectMessageBroadcaster;
import com.mopl.directmessage.websocket.DirectMessageRelayPublisher;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DirectMessageReadRealtimeListenerTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    @Mock
    private DirectMessageBroadcaster broadcaster;

    @Mock
    private DirectMessageRelayPublisher relayPublisher;

    @InjectMocks
    private DirectMessageReadRealtimeListener listener;

    @Test
    @DisplayName("DM 읽음 이벤트를 WebSocket과 Redis로 전달")
    void sendReadEvent_success() {
        // given
        DirectMessageReadEvent event =
            createReadEvent();

        // when
        listener.sendReadEvent(event);

        // then
        verify(broadcaster)
            .broadcastRead(
                CONVERSATION_ID,
                event
            );

        verify(relayPublisher)
            .publishRead(
                CONVERSATION_ID,
                event
            );
    }

    @Test
    @DisplayName("로컬 WebSocket 전송이 실패해도 Redis 중계를 시도")
    void sendReadEvent_broadcastFailure_publishesRelay() {
        // given
        DirectMessageReadEvent event =
            createReadEvent();

        doThrow(new RuntimeException("전송 실패"))
            .when(broadcaster)
            .broadcastRead(
                CONVERSATION_ID,
                event
            );

        // when
        listener.sendReadEvent(event);

        // then
        verify(relayPublisher)
            .publishRead(
                CONVERSATION_ID,
                event
            );
    }

    private DirectMessageReadEvent createReadEvent() {
        return new DirectMessageReadEvent(
            CONVERSATION_ID,
            UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
            ),
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            ),
            Instant.parse(
                "2026-08-27T01:00:00Z"
            )
        );
    }
}
