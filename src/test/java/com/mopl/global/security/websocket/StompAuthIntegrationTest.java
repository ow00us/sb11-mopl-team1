package com.mopl.global.security.websocket;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class StompAuthIntegrationTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtProvider jwtProvider;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
    }

    @Test
    @DisplayName("유효한 토큰으로 CONNECT하면 세션이 수립, 구독 가능해짐")
    void connectWithValidToken_thenSubscribeSucceeds() throws Exception {
        String token = "valid-token";
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            "user-id", null, List.of());
        when(jwtProvider.validate(token)).thenReturn(true);
        when(jwtProvider.getAuthentication(token)).thenReturn(authentication);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = stompClient
            .connectAsync(wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);

        assertThat(session.isConnected()).isTrue();

        CompletableFuture<String> received = new CompletableFuture<>();
        session.subscribe("/sub/contents/00000000-0000-0000-0000-000000000000/chat",
            new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                    received.complete((String) payload);
                }
            });

        // 구독 자체가 예외 없이 이루어지는지만 확인
        assertThat(session.isConnected()).isTrue();
    }

    @Test
    @DisplayName("Authorization 헤더 없이 CONNECT하면 연결 거부")
    void connectWithoutToken_connectionRejected() {
        StompHeaders connectHeaders = new StompHeaders();

        CompletableFuture<StompSession> future = stompClient.connectAsync(
            wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() {});

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(ConnectionLostException.class);
    }

    @Test
    @DisplayName("무효한 토큰으로 CONNECT하면 커스텀 ErrorResponse JSON이 포함된 ERROR 프레임 반환")
    void connectWithInvalidToken_returnsCustomErrorResponse() throws Exception{
        // given
        String token = "invalid-token";
        when(jwtProvider.validate(token)).thenReturn(false);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<String> errorPayloadFuture = new CompletableFuture<>();

        StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, @Nullable StompCommand command,
                StompHeaders headers, byte[] payload, Throwable exception) {
                if (StompCommand.ERROR.equals(command)) {
                    errorPayloadFuture.complete(new String(payload, StandardCharsets.UTF_8));
                }
            }
        };

        // when
        CompletableFuture<StompSession> connectFuture = stompClient.connectAsync(
            wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, sessionHandler);

        // then
        assertThatThrownBy(() -> connectFuture.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(ConnectionLostException.class);

        // 에러 핸들러가 가로챈 JSON 페이로드 검증
        String errorPayload = errorPayloadFuture.get(5, TimeUnit.SECONDS);
        // 에러 코드 값 검증
        assertThat(errorPayload).contains("\"errorCode\":\"" + ErrorCode.UNAUTHORIZED.getCode() + "\"");
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    @Test
    @DisplayName("SockJs 전체 경로로 CONNECT해도 인증이 작동")
    void connectViaSockJs_withValidToken_succeeds() throws Exception {
        String token = "valid-token";
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            "user-id", null, List.of());
        when(jwtProvider.validate(token)).thenReturn(true);
        when(jwtProvider.getAuthentication(token)).thenReturn(authentication);

        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        WebSocketStompClient sockJsStompClient = new WebSocketStompClient(new SockJsClient(transports));
        sockJsStompClient.setMessageConverter(new StringMessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        String sockJsUrl = "http://localhost:" + port + "/ws";

        StompSession session = sockJsStompClient
            .connectAsync(sockJsUrl, (WebSocketHttpHeaders) null, connectHeaders,
                new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);

        assertThat(session.isConnected()).isTrue();
    }
}
