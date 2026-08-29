package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.dto.DirectMessageSendRequest;
import com.mopl.directmessage.service.DirectMessageService;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DirectMessageWebSocketControllerTest {

    private static final UUID CONVERSATION_ID =
        UUID.fromString(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        );

    private static final UUID SENDER_ID =
        UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
        );

    private static final UUID RECEIVER_ID =
        UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
        );

    private static final UUID CLIENT_MESSAGE_ID =
        UUID.fromString(
            "cccccccc-cccc-cccc-cccc-cccccccccccc"
        );

    @Mock
    private DirectMessageService directMessageService;

    @Mock
    private DirectMessageBroadcaster broadcaster;

    @Mock
    private DirectMessageRelayPublisher relayPublisher;

    @InjectMocks
    private DirectMessageWebSocketController controller;

    @Test
    @DisplayName("WebSocket DM을 저장한 후 대화방 구독자에게 전송한다")
    void send_success() {
        // given
        DirectMessageSendRequest request =
            new DirectMessageSendRequest(
                CLIENT_MESSAGE_ID,
                "실시간 메시지"
            );

        Principal principal =
            () -> SENDER_ID.toString();

        DirectMessageDto savedMessage =
            createMessageDto();

        when(
            directMessageService.create(
                SENDER_ID,
                CONVERSATION_ID,
                request.clientMessageId(),
                request.content()
            )
        ).thenReturn(savedMessage);

        // when
        controller.send(
            CONVERSATION_ID,
            request,
            principal
        );

        // then
        // DB 저장 서비스가 먼저 실행되고 실시간 전송이 나중에
        // 실행되는지도 호출 순서로 검증한다.
        InOrder inOrder =
            inOrder(
                directMessageService,
                broadcaster,
                relayPublisher
            );

        inOrder.verify(directMessageService)
            .create(
                SENDER_ID,
                CONVERSATION_ID,
                request.clientMessageId(),
                request.content()
            );

        inOrder.verify(broadcaster)
            .broadcast(
                CONVERSATION_ID,
                savedMessage
            );

        inOrder.verify(relayPublisher)
            .publish(
                CONVERSATION_ID,
                savedMessage
            );
    }

    @Test
    @DisplayName("인증 사용자가 없으면 WebSocket DM을 전송할 수 없다")
    void send_noPrincipal_fails() {
        // given
        DirectMessageSendRequest request =
            new DirectMessageSendRequest(
                "실시간 메시지"
            );

        // when & then
        assertThatThrownBy(() ->
            controller.send(
                CONVERSATION_ID,
                request,
                null
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.UNAUTHORIZED
                        )
            );

        verifyNoInteractions(
            directMessageService,
            broadcaster,
            relayPublisher
        );
    }

    @Test
    @DisplayName("DM 저장에 실패하면 WebSocket으로 전송하지 않는다")
    void send_saveFailure_doesNotBroadcast() {
        // given
        DirectMessageSendRequest request =
            new DirectMessageSendRequest(
                "실시간 메시지"
            );

        Principal principal =
            () -> SENDER_ID.toString();

        when(
            directMessageService.create(
                SENDER_ID,
                CONVERSATION_ID,
                request.clientMessageId(),
                request.content()
            )
        ).thenThrow(
            new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            )
        );

        // when & then
        assertThatThrownBy(() ->
            controller.send(
                CONVERSATION_ID,
                request,
                principal
            )
        ).isInstanceOf(BusinessException.class);

        // 저장에 실패하면 전송 컴포넌트는 호출되지 않는다.
        verifyNoInteractions(
            broadcaster,
            relayPublisher
        );
    }

    private DirectMessageDto createMessageDto() {
        UserSummary sender =
            new UserSummary(
                SENDER_ID,
                "발신자",
                null
            );

        UserSummary receiver =
            new UserSummary(
                RECEIVER_ID,
                "수신자",
                null
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
            sender,
            receiver,
            "실시간 메시지",
            null,
            CLIENT_MESSAGE_ID
        );
    }

    @Test
    @DisplayName("인증 사용자 이름이 없으면 WebSocket DM을 전송할 수 없다")
    void send_nullPrincipalName_fails() {
        // given
        DirectMessageSendRequest request =
            new DirectMessageSendRequest(
                "실시간 메시지"
            );

        Principal principal =
            () -> null;

        // when & then
        assertThatThrownBy(() ->
            controller.send(
                CONVERSATION_ID,
                request,
                principal
            )
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.UNAUTHORIZED
                        )
            );

        verifyNoInteractions(
            directMessageService,
            broadcaster,
            relayPublisher
        );
    }
}
