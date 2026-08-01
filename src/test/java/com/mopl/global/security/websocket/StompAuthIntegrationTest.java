package com.mopl.global.security.websocket;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.exception.ErrorResponse;
import com.mopl.global.security.JwtProvider;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.lang.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.ConnectionLostException;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class StompAuthIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final long[] CLIENT_HEARTBEAT = {4000, 4000};

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtProvider jwtProvider;

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;
    private StompSession session;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void setUp() {
        taskScheduler = createTaskScheduler();
        stompClient = createNativeStompClime();
    }

    private WebSocketStompClient createNativeStompClime() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        client.setTaskScheduler(taskScheduler);
        client.setDefaultHeartbeat(CLIENT_HEARTBEAT);
        return client;
    }

    private WebSocketStompClient createSockJsStompClime() {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        WebSocketStompClient client = new WebSocketStompClient(new SockJsClient(transports));
        client.setMessageConverter(new StringMessageConverter());
        client.setTaskScheduler(taskScheduler);
        client.setDefaultHeartbeat(CLIENT_HEARTBEAT);
        return client;
    }
    private ThreadPoolTaskScheduler createTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-test-client-");
        scheduler.initialize();
        return scheduler;
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
        if (stompClient != null) {
            stompClient.stop();
        }
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Test
    @DisplayName("유효한 토큰으로 CONNECT하면 세션이 수립, 구독한 메시지를 수신함")
    void connectWithValidToken_thenSubscribeSucceeds() throws Exception {
        String token = "valid-token";
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            "user-id", null, List.of());
        when(jwtProvider.validate(token)).thenReturn(true);
        when(jwtProvider.getAuthentication(token)).thenReturn(authentication);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        session = stompClient
            .connectAsync(wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);

        assertThat(session.isConnected()).isTrue();

        String destination = "/sub/contents/00000000-0000-0000-0000-000000000000/chat";
        CompletableFuture<String> received = new CompletableFuture<>();

        session.subscribe(destination, new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                    received.complete((String) payload);
                }
            });

        // 등록될 때까지 짧은 간격으로 재발행하며 첫 수신 대기
        long deadline = System.currentTimeMillis() + 5000;
        while (!received.isDone() && System.currentTimeMillis() < deadline) {
            messagingTemplate.convertAndSend(destination, "hello");
            Thread.sleep(100);
        }

        assertThat(received.get(1, TimeUnit.SECONDS)).isEqualTo("hello");
    }

    @Test
    @DisplayName("Authorization 헤더 없이 CONNECT하면 UNAUTHORIZED ErrorResponse와 함께 연결 거부")
    void connectWithoutToken_connectionRejected() throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        CompletableFuture<ErrorResponse> errorResponseFuture = new CompletableFuture<>();

        StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    errorResponseFuture.complete(objectMapper.readValue((String) payload, ErrorResponse.class));
                } catch (IOException e) {
                    errorResponseFuture.completeExceptionally(e);
                }
            }
        };
        CompletableFuture<StompSession> future = stompClient.connectAsync(
            wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, sessionHandler);

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause()
            .isInstanceOf(ConnectionLostException.class);

        ErrorResponse errorResponse = errorResponseFuture.get(5, TimeUnit.SECONDS);
        assertThat(errorResponse.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("무효한 토큰으로 CONNECT하면 커스텀 ErrorResponse JSON이 포함된 ERROR 프레임 반환")
    void connectWithInvalidToken_returnsCustomErrorResponse() throws Exception{
        // given
        String token = "invalid-token";
        when(jwtProvider.validate(token)).thenReturn(false);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        CompletableFuture<ErrorResponse> errorResponseFuture = new CompletableFuture<>();

        StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    errorResponseFuture.complete(objectMapper.readValue((String) payload, ErrorResponse.class));
                } catch (IOException e) {
                    errorResponseFuture.completeExceptionally(e);
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

        ErrorResponse errorResponse = errorResponseFuture.get(5, TimeUnit.SECONDS);
        assertThat(errorResponse.errorCode()).isEqualTo(ErrorCode.UNAUTHORIZED.getCode());
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

        stompClient = createSockJsStompClime();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        String sockJsUrl = "http://localhost:" + port + "/ws";

        session = stompClient
            .connectAsync(sockJsUrl, (WebSocketHttpHeaders) null, connectHeaders,
                new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);

        assertThat(session.isConnected()).isTrue();
    }
}
