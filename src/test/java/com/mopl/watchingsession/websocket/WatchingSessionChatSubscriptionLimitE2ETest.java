package com.mopl.watchingsession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.security.JwtProvider;
import com.mopl.support.websocket.StompTestCleanup;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.dto.ContentChatSendRequest;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSession.Subscription;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * chat 구독 개수 상한(테스트 프로파일: 연결당 3개)이 실제 STOMP 체인에서 동작하는지 검증한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class WatchingSessionChatSubscriptionLimitE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
        .withExposedPorts(6379);

    private static final long[] CLIENT_HEARTBEAT = {4000, 4000};
    private static final long SUBSCRIBE_SETTLE_MILLIS = 300;

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private UserRepository userRepository;

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;
    private StompSession session;
    private StompSession senderSession;

    private UUID watcherId;
    private UUID senderId;

    @BeforeEach
    void setUp() {
        taskScheduler = createTaskScheduler();
        stompClient = createNativeStompClient();

        User watcher = userRepository.save(User.builder()
            .email("chat-sub-limit-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("구독상한테스트유저")
            .role(UserRole.USER)
            .locked(false)
            .build());
        watcherId = watcher.getId();

        User sender = userRepository.save(User.builder()
            .email("chat-sub-limit-sender-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("구독상한발신자")
            .role(UserRole.USER)
            .locked(false)
            .build());
        senderId = sender.getId();
    }

    @AfterEach
    void tearDown() {
        try {
            StompTestCleanup.closeAll(stompClient, taskScheduler, session, senderSession);
        } finally {
            contentRepository.deleteAll();
            userRepository.deleteAll();
        }
    }

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
        scheduler.setThreadNamePrefix("chat-sub-limit-client-");
        scheduler.initialize();
        return scheduler;
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private UUID createContent(String title) {
        Content content = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title(title)
            .description("설명")
            .build());
        return content.getId();
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

    private record ChatSubscription(
        Subscription subscription,
        CompletableFuture<ContentChatDto> future
    ) {}

    private ChatSubscription subscribeChatAndCapture(StompSession session, UUID contentId) {
        CompletableFuture<ContentChatDto> future = new CompletableFuture<>();
        Subscription subscription =
            session.subscribe("/sub/contents/" + contentId + "/chat", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return ContentChatDto.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    future.complete((ContentChatDto) payload);
                }
            });
        return new ChatSubscription(subscription, future);
    }

    @Test
    @DisplayName("[E2E] 상한(3개)을 넘는 네 번째 chat 구독은 등록되지 않아 해당 콘텐츠 채팅 메시지를 받지 못한다")
    void fourthChatSubscription_neverReceivesBroadcast_whenExceedingLimit() throws Exception {
        session = connectAs(watcherId, null);
        UUID content1 = createContent("채팅상한1");
        UUID content2 = createContent("채팅상한2");
        UUID content3 = createContent("채팅상한3");
        UUID content4 = createContent("채팅상한4");

        subscribeChatAndCapture(session, content1);
        subscribeChatAndCapture(session, content2);
        subscribeChatAndCapture(session, content3);
        Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);

        CompletableFuture<ContentChatDto> fourth = subscribeChatAndCapture(session, content4).future();
        Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);

        senderSession = connectAs(senderId, null);
        senderSession.send("/pub/contents/" + content4 + "/chat",
            new ContentChatSendRequest("도달하면 안 되는 메시지"));

        await().during(2, TimeUnit.SECONDS)
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(fourth.isDone()).isFalse());
    }

    @Test
    @DisplayName("[E2E] 상한(3) 이내에서 콘텐츠를 순차 이동(구독→해제→재구독)하며 시청해도 "
        + "매번 정상적으로 메시지를 수신한다 (오탐 없음)")
    void sequentiallyMovingAcrossContents_neverBlocked_evenBeyondLimitCountOverTime() throws Exception {
        session = connectAs(watcherId, null);
        senderSession = connectAs(senderId, null);

        for (int i = 0; i < 5; i++) {
            UUID contentId = createContent("순차이동콘텐츠" + i);

            // sender가 이 콘텐츠를 실제로 시청 중이어야 채팅 SEND가 통과한다
            // (ContentChatService의 isWatching 검증). watch SUBSCRIBE는 연결당 활성 세션이
            // 1개로 swap되므로, 반복마다 다음 콘텐츠로 갈아타는 것이 정상 시청 흐름이다.
            senderSession.subscribe("/sub/contents/" + contentId + "/watch", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                }
            });

            ChatSubscription chatSub = subscribeChatAndCapture(session, contentId);

            // watch 재구독 최소 간격(테스트 프로파일 200ms)과 구독 안착 시간을 함께 확보
            Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);

            senderSession.send("/pub/contents/" + contentId + "/chat",
                new ContentChatSendRequest("순차이동 메시지 " + i));

            ContentChatDto dto = chatSub.future().get(3, TimeUnit.SECONDS);
            assertThat(dto).isNotNull();

            chatSub.subscription().unsubscribe();
            Thread.sleep(100); // UNSUBSCRIBE 처리(chat 구독 카운트 감소)를 서버가 반영할 시간
        }
    }
}
