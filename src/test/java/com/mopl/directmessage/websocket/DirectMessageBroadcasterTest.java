package com.mopl.directmessage.websocket;

import static org.mockito.Mockito.verify;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.global.common.UserSummary;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class DirectMessageBroadcasterTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private DirectMessageBroadcaster broadcaster;

    @Test
    @DisplayName("저장된 DM을 해당 대화방 구독 경로로 전송한다")
    void broadcast_success() {
        // given
        DirectMessageDto message =
            createMessageDto();

        String destination =
            "/sub/conversations/"
                + CONVERSATION_ID
                + "/direct-messages";

        // when
        broadcaster.broadcast(
            CONVERSATION_ID,
            message
        );

        // then
        verify(messagingTemplate)
            .convertAndSend(
                destination,
                message
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
                "2026-08-04T01:00:00Z"
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
