package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.dto.DirectMessageReadEvent;
import com.mopl.global.common.UserSummary;
import com.mopl.global.realtime.RealtimeMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DirectMessageRelayHandlerTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private DirectMessageBroadcaster broadcaster;

    @InjectMocks
    private DirectMessageRelayHandler handler;

    @Test
    @DisplayName("DM 생성 실시간 이벤트를 처리 대상으로 판단")
    void supports_directMessageCreated_returnsTrue() {
        // when
        boolean result =
            handler.supports(
                DirectMessageRealtimeContract.CREATED_EVENT_TYPE
            );

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("DM 읽음 실시간 이벤트를 처리 대상으로 판단")
    void supports_directMessageRead_returnsTrue() {
        // when
        boolean result =
            handler.supports(
                DirectMessageRealtimeContract.READ_EVENT_TYPE
            );

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("DM 생성 이외의 실시간 이벤트는 처리하지 않음")
    void supports_otherEvent_returnsFalse() {
        // when
        boolean result =
            handler.supports(
                "notification.created"
            );

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("다른 서버에서 받은 DM을 현재 서버 구독자에게 전송")
    void handle_success() {
        // given
        DirectMessageDto message =
            createMessageDto();

        JsonNode payload =
            mock(JsonNode.class);

        String destination =
            DirectMessageRealtimeContract.destination(
                CONVERSATION_ID
            );

        RealtimeMessage relayMessage =
            new RealtimeMessage(
                UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
                ),
                "remote-instance",
                DirectMessageRealtimeContract.CREATED_EVENT_TYPE,
                destination,
                payload
            );

        when(
            objectMapper.convertValue(
                payload,
                DirectMessageDto.class
            )
        ).thenReturn(message);

        // when
        handler.handle(relayMessage);

        // then
        verify(broadcaster)
            .broadcast(
                destination,
                message
            );
    }

    @Test
    @DisplayName("다른 서버에서 받은 DM 읽음 상태를 현재 서버 구독자에게 전송")
    void handle_readEvent_success() {
        // given
        DirectMessageReadEvent readEvent =
            createReadEvent();

        JsonNode payload =
            mock(JsonNode.class);

        String destination =
            DirectMessageRealtimeContract.destination(
                CONVERSATION_ID
            );

        RealtimeMessage relayMessage =
            new RealtimeMessage(
                UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
                ),
                "remote-instance",
                DirectMessageRealtimeContract.READ_EVENT_TYPE,
                destination,
                payload
            );

        when(
            objectMapper.convertValue(
                payload,
                DirectMessageReadEvent.class
            )
        ).thenReturn(readEvent);

        // when
        handler.handle(relayMessage);

        // then
        verify(broadcaster)
            .broadcastRead(
                destination,
                readEvent
            );
    }

    @Test
    @DisplayName("DM payload와 WebSocket 목적지가 다르면 전송에 실패")
    void handle_destinationMismatch_fails() {
        // given
        DirectMessageDto message =
            createMessageDto();

        JsonNode payload =
            mock(JsonNode.class);

        RealtimeMessage relayMessage =
            new RealtimeMessage(
                UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
                ),
                "remote-instance",
                DirectMessageRealtimeContract.CREATED_EVENT_TYPE,
                "/sub/conversations/"
                    + UUID.fromString(
                        "dddddddd-dddd-dddd-dddd-dddddddddddd"
                    )
                    + "/direct-messages",
                payload
            );

        when(
            objectMapper.convertValue(
                payload,
                DirectMessageDto.class
            )
        ).thenReturn(message);

        // when & then
        assertThatThrownBy(() ->
            handler.handle(relayMessage)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "DM 실시간 메시지의 목적지가 올바르지 않습니다."
            );

        verifyNoInteractions(broadcaster);
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

    private DirectMessageDto createMessageDto() {
        UUID senderId =
            UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
            );

        UUID receiverId =
            UUID.fromString(
                "22222222-2222-2222-2222-222222222222"
            );

        return new DirectMessageDto(
            UUID.fromString(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            ),
            CONVERSATION_ID,
            Instant.parse(
                "2026-08-20T01:00:00Z"
            ),
            1L,
            new UserSummary(
                senderId,
                "발신자",
                null
            ),
            new UserSummary(
                receiverId,
                "수신자",
                null
            ),
            "실시간 메시지",
            null,
            UUID.fromString(
                "dddddddd-dddd-dddd-dddd-dddddddddddd"
            )
        );
    }
}
