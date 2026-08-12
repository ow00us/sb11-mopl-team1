package com.mopl.global.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class StompDestinationAuthorizationInterceptorTest {

    private static final UUID RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final StompDestinationAuthorizationInterceptor interceptor =
        new StompDestinationAuthorizationInterceptor();

    @ParameterizedTest
    @ValueSource(strings = {
        "/sub/contents/00000000-0000-0000-0000-000000000001/watch",
        "/sub/contents/00000000-0000-0000-0000-000000000001/chat",
        "/sub/conversations/00000000-0000-0000-0000-000000000001/direct-messages"
    })
    @DisplayName("인증 사용자는 계약에 등록된 목적지를 구독할 수 있음")
    void subscribe_allowedDestination_passes(String destination) {
        Message<?> message = stompMessage(StompCommand.SUBSCRIBE, destination, authenticatedPrincipal());

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/pub/contents/00000000-0000-0000-0000-000000000001/chat",
        "/pub/contents/00000000-0000-0000-0000-000000000001/watch/heartbeat",
        "/pub/conversations/00000000-0000-0000-0000-000000000001/direct-messages"
    })
    @DisplayName("인증 사용자는 계약에 등록된 애플리케이션 목적지로 송신할 수 있음")
    void send_allowedDestination_passes(String destination) {
        Message<?> message = stompMessage(StompCommand.SEND, destination, authenticatedPrincipal());

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    @Test
    @DisplayName("클라이언트가 브로커 목적지로 직접 송신하면 FORBIDDEN")
    void send_directlyToBrokerDestination_throwsForbidden() {
        Message<?> message = stompMessage(
            StompCommand.SEND,
            "/sub/contents/" + RESOURCE_ID + "/chat",
            authenticatedPrincipal()
        );

        assertForbidden(message);
    }

    @Test
    @DisplayName("등록되지 않은 애플리케이션 목적지로 송신하면 FORBIDDEN")
    void send_unknownApplicationDestination_throwsForbidden() {
        Message<?> message = stompMessage(
            StompCommand.SEND,
            "/pub/verification/anything",
            authenticatedPrincipal()
        );

        assertForbidden(message);
    }

    @Test
    @DisplayName("등록되지 않은 브로커 목적지를 구독하면 FORBIDDEN")
    void subscribe_unknownDestination_throwsForbidden() {
        Message<?> message = stompMessage(
            StompCommand.SUBSCRIBE,
            "/sub/verification/private",
            authenticatedPrincipal()
        );

        assertForbidden(message);
    }

    @Test
    @DisplayName("목적지 식별자가 UUID 형식이 아니면 FORBIDDEN")
    void subscribe_malformedResourceId_throwsForbidden() {
        Message<?> message = stompMessage(
            StompCommand.SUBSCRIBE,
            "/sub/contents/not-a-uuid/watch",
            authenticatedPrincipal()
        );

        assertForbidden(message);
    }

    @Test
    @DisplayName("인증 정보 없이 허용 목적지를 구독하면 UNAUTHORIZED")
    void subscribe_withoutPrincipal_throwsUnauthorized() {
        Message<?> message = stompMessage(
            StompCommand.SUBSCRIBE,
            "/sub/contents/" + RESOURCE_ID + "/watch",
            null
        );

        assertThatThrownBy(() -> interceptor.preSend(message, null))
            .isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("CONNECT와 연결 정리 명령은 목적지 인가 대상이 아님")
    void nonDestinationCommands_pass() {
        Message<?> connect = stompMessage(StompCommand.CONNECT, null, null);
        Message<?> disconnect = stompMessage(StompCommand.DISCONNECT, null, authenticatedPrincipal());

        assertThat(interceptor.preSend(connect, null)).isSameAs(connect);
        assertThat(interceptor.preSend(disconnect, null)).isSameAs(disconnect);
    }

    @Test
    @DisplayName("heartbeat 목적지를 SUBSCRIBE로 시도하면 FORBIDDEN (SEND 전용 목적지)")
    void subscribe_heartbeatDestination_throwsForbidden() {
        Message<?> message = stompMessage(
            StompCommand.SUBSCRIBE,
            "/sub/contents/" + RESOURCE_ID + "/watch/heartbeat",
            authenticatedPrincipal()
        );

        assertForbidden(message);
    }

    @Test
    @DisplayName("heartbeat 목적지의 리소스 식별자가 UUID 형식이 아니면 FORBIDDEN")
    void send_malformedHeartbeatResourceId_throwsForbidden() {
        Message<?> message = stompMessage(
            StompCommand.SEND,
            "/pub/contents/not-a-uuid/watch/heartbeat",
            authenticatedPrincipal()
        );

        assertForbidden(message);
    }

    @Test
    @DisplayName("인증 정보 없이 heartbeat 목적지로 송신하면 UNAUTHORIZED")
    void send_heartbeatDestinationWithoutAuth_throwsUnauthorized() {
        Message<?> message = stompMessage(
            StompCommand.SEND,
            "/pub/contents/" + RESOURCE_ID + "/watch/heartbeat",
            null
        );

        assertThatThrownBy(() -> interceptor.preSend(message, null))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private void assertForbidden(Message<?> message) {
        assertThatThrownBy(() -> interceptor.preSend(message, null))
            .isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).getErrorCode())
            .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private Principal authenticatedPrincipal() {
        return UsernamePasswordAuthenticationToken.authenticated(
            UUID.randomUUID(),
            null,
            List.of()
        );
    }

    private Message<?> stompMessage(StompCommand command, String destination, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(principal);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
