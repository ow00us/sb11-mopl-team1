package com.mopl.watchingsession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import com.mopl.watchingsession.service.WatchingSessionSnapshotWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 이슈 #137 회귀 방지 테스트.
 *
 * WatchingSessionSubscribeListener.onSubscribe()에서 watchingSessionService.start()가
 * BusinessException이 아닌 인프라 예외(DB 타임아웃 등 RuntimeException)로 실패하는 경우,
 * 클라이언트는 실패를 통보받아야 하고 브로커에 등록된 구독도 정리되어야 한다.
 *
 * 기대 동작:
 * 1. 클라이언트가 STOMP ERROR 프레임을 수신한다 (INTERNAL_ERROR).
 * 2. 서버가 세션을 강제 종료하여 브로커가 SessionDisconnectEvent 로 구독을 자동 정리한다.
 * 3. DB 시청 세션은 생성되지 않는다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WatchingSessionSubscribeInfraFailureIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final long[] CLIENT_HEARTBEAT = {4000, 4000};

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtProvider jwtProvider;

    // 인프라 예외를 강제로 유발하기 위해 upsert() 만 mock
    @MockitoBean
    private WatchingSessionSnapshotWriter watchingSessionSnapshotWriter;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WatchingSessionSnapshotRepository snapshotRepository;

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;
    private StompSession session;

    private UUID watcherId;
    private UUID contentId;

    @BeforeEach
    void setUp() {
        taskScheduler = createTaskScheduler();
        stompClient = createNativeStompClient();

        User watcher = userRepository.save(User.builder()
            .email("infra-fail-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("인프라실패유저")
            .role(UserRole.USER)
            .locked(false)
            .build());
        watcherId = watcher.getId();

        Content content = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title("인프라 실패 재현 콘텐츠")
            .description("설명")
            .build());
        contentId = content.getId();
    }

    @AfterEach
    void tearDown() {
        if (session != null && session.isConnected()) {
            try {
                session.disconnect();
            } catch (MessageDeliveryException ignored) {
                // ERROR 처리 후 서버가 먼저 닫을 수 있음
            }
        }
        if (stompClient != null) {
            stompClient.stop();
        }
        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
        snapshotRepository.deleteAll();
        contentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("[#137 회귀] start() 인프라 예외 시 ERROR 프레임이 전달되고 세션이 종료되어 브로커 구독이 정리된다")
    void watchSubscribe_infraException_receivesErrorAndClosesSession() throws Exception {
        // given: upsert()가 인프라 예외를 던지도록 mock (BusinessException 이 아닌 RuntimeException)
        when(watchingSessionSnapshotWriter.upsert(any(), any(), any()))
            .thenThrow(new RuntimeException("DB 타임아웃 시뮬레이션"));

        CompletableFuture<String> errorReceived = new CompletableFuture<>();
        session = connectAs(watcherId, errorReceived);

        String watchDestination = "/sub/contents/" + contentId + "/watch";

        // when: watch 토픽 구독 - onSubscribe 안에서 start() 가 RuntimeException 으로 실패
        session.subscribe(watchDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                // 정상 프레임은 오지 않아야 함
            }
        });

        // then 1: ERROR 프레임이 INTERNAL_ERROR 로 전달됨
        String errorPayload = errorReceived.get(5, TimeUnit.SECONDS);
        assertThat(errorPayload).contains(ErrorCode.INTERNAL_ERROR.getCode());

        // then 2: 세션이 종료되어 브로커가 구독을 자동 정리함
        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(() -> !session.isConnected());

        // then 3: DB 시청 세션은 생성되지 않음 (start() 초기 단계에서 실패)
        assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty();
    }

    // ── 헬퍼 (E2E 회귀 테스트와 동일 패턴) ─────────────────────────────────────────

    private WebSocketStompClient createNativeStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        converter.setObjectMapper(objectMapper);
        client.setMessageConverter(converter);
        client.setTaskScheduler(taskScheduler);
        client.setDefaultHeartbeat(CLIENT_HEARTBEAT);
        return client;
    }

    private ThreadPoolTaskScheduler createTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("watch-infra-fail-client-");
        scheduler.initialize();
        return scheduler;
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private StompSession connectAs(UUID userId, CompletableFuture<String> errorFuture) throws Exception {
        String token = "valid-token-" + userId;
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            userId.toString(), null, List.of());
        when(jwtProvider.validate(token)).thenReturn(true);
        when(jwtProvider.getAuthentication(token)).thenReturn(authentication);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        return stompClient
            .connectAsync(wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    if (errorFuture != null) {
                        errorFuture.complete(new String((byte[]) payload, StandardCharsets.UTF_8));
                    }
                }

                @Override
                public void handleException(StompSession session, StompCommand command,
                    StompHeaders headers, byte[] payload, Throwable exception) {
                    if (errorFuture != null && command == StompCommand.ERROR) {
                        errorFuture.complete(new String(payload, StandardCharsets.UTF_8));
                    }
                }
            })
            .get(5, TimeUnit.SECONDS);
    }
}
