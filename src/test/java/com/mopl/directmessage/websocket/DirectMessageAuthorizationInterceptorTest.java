package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class DirectMessageAuthorizationInterceptorTest {

    private static final UUID CONVERSATION_ID =
        UUID.randomUUID();

    private static final UUID USER_ID =
        UUID.randomUUID();

    @Mock
    private ConversationParticipantRepository
        participantRepository;

    private DirectMessageAuthorizationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor =
            new DirectMessageAuthorizationInterceptor(
                participantRepository
            );
    }

    @Test
    @DisplayName("대화 참여자는 DM을 전송할 수 있다")
    void send_participant_succeeds() {
        // given
        Message<?> message = createMessage(
            StompCommand.SEND,
            "/pub/conversations/"
                + CONVERSATION_ID
                + "/direct-messages",
            USER_ID
        );

        when(
            participantRepository
                .existsByConversationIdAndUserId(
                    CONVERSATION_ID,
                    USER_ID
                )
        ).thenReturn(true);

        // when
        Message<?> result =
            interceptor.preSend(message, null);

        // then
        assertThat(result).isEqualTo(message);
    }

    @Test
    @DisplayName("대화 참여자는 DM을 구독할 수 있다")
    void subscribe_participant_succeeds() {
        // given
        Message<?> message = createMessage(
            StompCommand.SUBSCRIBE,
            "/sub/conversations/"
                + CONVERSATION_ID
                + "/direct-messages",
            USER_ID
        );

        when(
            participantRepository
                .existsByConversationIdAndUserId(
                    CONVERSATION_ID,
                    USER_ID
                )
        ).thenReturn(true);

        // when
        Message<?> result =
            interceptor.preSend(message, null);

        // then
        assertThat(result).isEqualTo(message);
    }

    @Test
    @DisplayName("비참여자는 DM을 전송할 수 없다")
    void send_nonParticipant_fails() {
        // given
        Message<?> message = createMessage(
            StompCommand.SEND,
            "/pub/conversations/"
                + CONVERSATION_ID
                + "/direct-messages",
            USER_ID
        );

        when(
            participantRepository
                .existsByConversationIdAndUserId(
                    CONVERSATION_ID,
                    USER_ID
                )
        ).thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
            interceptor.preSend(message, null)
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.RESOURCE_NOT_FOUND
                        )
            );
    }

    @Test
    @DisplayName("비참여자는 DM을 구독할 수 없다")
    void subscribe_nonParticipant_fails() {
        // given
        Message<?> message = createMessage(
            StompCommand.SUBSCRIBE,
            "/sub/conversations/"
                + CONVERSATION_ID
                + "/direct-messages",
            USER_ID
        );

        when(
            participantRepository
                .existsByConversationIdAndUserId(
                    CONVERSATION_ID,
                    USER_ID
                )
        ).thenReturn(false);

        // when & then
        assertThatThrownBy(() ->
            interceptor.preSend(message, null)
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.RESOURCE_NOT_FOUND
                        )
            );
    }

    @Test
    @DisplayName("대화 ID가 UUID 형식이 아니면 실패한다")
    void invalidConversationId_fails() {
        // given
        Message<?> message = createMessage(
            StompCommand.SEND,
            "/pub/conversations/not-uuid/direct-messages",
            USER_ID
        );

        // when & then
        assertThatThrownBy(() ->
            interceptor.preSend(message, null)
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.INVALID_INPUT
                        )
            );
    }

    @Test
    @DisplayName("인증 사용자가 없으면 DM 요청에 실패한다")
    void noPrincipal_fails() {
        // given
        Message<?> message = createMessageWithoutUser(
            StompCommand.SEND,
            "/pub/conversations/"
                + CONVERSATION_ID
                + "/direct-messages"
        );

        // when & then
        assertThatThrownBy(() ->
            interceptor.preSend(message, null)
        )
            .isInstanceOfSatisfying(
                BusinessException.class,
                exception ->
                    assertThat(exception.getErrorCode())
                        .isEqualTo(
                            ErrorCode.UNAUTHORIZED
                        )
            );
    }

    @Test
    @DisplayName("DM이 아닌 WebSocket 경로는 참여 여부를 검사하지 않는다")
    void nonDirectMessageDestination_skipsAuthorization() {
        // given
        Message<?> message = createMessage(
            StompCommand.SEND,
            "/pub/contents/"
                + UUID.randomUUID()
                + "/chat",
            USER_ID
        );

        // when
        Message<?> result =
            interceptor.preSend(message, null);

        // then
        assertThat(result).isEqualTo(message);

        verify(
            participantRepository,
            never()
        ).existsByConversationIdAndUserId(
            CONVERSATION_ID,
            USER_ID
        );
    }

    private Message<?> createMessage(
        StompCommand command,
        String destination,
        UUID userId
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.create(command);

        accessor.setDestination(destination);
        accessor.setUser(
            UsernamePasswordAuthenticationToken
                .authenticated(
                    userId,
                    null,
                    List.of()
                )
        );
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
            new byte[0],
            accessor.getMessageHeaders()
        );
    }

    private Message<?> createMessageWithoutUser(
        StompCommand command,
        String destination
    ) {
        StompHeaderAccessor accessor =
            StompHeaderAccessor.create(command);

        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);

        return MessageBuilder.createMessage(
            new byte[0],
            accessor.getMessageHeaders()
        );
    }
}
