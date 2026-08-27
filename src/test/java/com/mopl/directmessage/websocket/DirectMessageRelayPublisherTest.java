package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.dto.DirectMessageReadEvent;
import com.mopl.global.common.UserSummary;
import com.mopl.global.realtime.RealtimeRelayPublisher;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DirectMessageRelayPublisherTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    @Mock
    private RealtimeRelayPublisher relayPublisher;

    @InjectMocks
    private DirectMessageRelayPublisher publisher;

    @Test
    @DisplayName("저장된 DM을 다른 서버로 중계")
    void publish_success() {
        // given
        DirectMessageDto message =
            createMessageDto();

        String destination =
            DirectMessageRealtimeContract.destination(
                CONVERSATION_ID
            );

        when(
            relayPublisher.publish(
                DirectMessageRealtimeContract.CREATED_EVENT_TYPE,
                destination,
                message
            )
        ).thenReturn(true);

        // when
        boolean result =
            publisher.publish(
                CONVERSATION_ID,
                message
            );

        // then
        assertThat(result).isTrue();

        verify(relayPublisher)
            .publish(
                DirectMessageRealtimeContract.CREATED_EVENT_TYPE,
                destination,
                message
            );
    }

    @Test
    @DisplayName("DM 읽음 상태를 다른 서버로 중계")
    void publishRead_success() {
        // given
        DirectMessageReadEvent readEvent =
            new DirectMessageReadEvent(
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

        String destination =
            DirectMessageRealtimeContract.destination(
                CONVERSATION_ID
            );

        when(
            relayPublisher.publish(
                DirectMessageRealtimeContract.READ_EVENT_TYPE,
                destination,
                readEvent
            )
        ).thenReturn(true);

        // when
        boolean result =
            publisher.publishRead(
                CONVERSATION_ID,
                readEvent
            );

        // then
        assertThat(result).isTrue();

        verify(relayPublisher)
            .publish(
                DirectMessageRealtimeContract.READ_EVENT_TYPE,
                destination,
                readEvent
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
            "실시간 메시지"
        );
    }
}
