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
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
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
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
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

    /**
     * SessionDisconnectEvent는 clientInboundChannel과 스레드풀을 공유한다.
     * 이 테스트는 한 연결에서 chat 구독을 여러 개 몰아붙이므로, CPU가 적은 환경에서는 기본 풀 크기가 밀려 정리 단계와 경합할 수 있다.
     * WatchingSessionE2ERegressionTest와 동일한 조치로 여유 스레드를 확보한다.
     */
    @TestConfiguration
    static class InboundChannelTestConfig implements WebSocketMessageBrokerConfigurer {

        @Override
        public void configureClientInboundChannel(ChannelRegistration registration) {
            registration.taskExecutor().corePoolSize(4);
        }
    }

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private WatchingSessionSnapshotRepository snapshotRepository;

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;

    /** 이 테스트에서 연 모든 STOMP 세션. tearDown에서 한꺼번에 정리한다. */
    private final List<StompSession> openSessions = new ArrayList<>();

    /** 이 테스트에서 만든 모든 watcherId. tearDown에서 presence 소멸을 확인할 대상이다. */
    private final List<UUID> createdWatcherIds = new ArrayList<>();

    private UUID watcherId;

    @BeforeEach
    void setUp() {
        taskScheduler = createTaskScheduler();
        stompClient = createNativeStompClient();
        watcherId = createWatcher("chat-sub-limit-");
    }

    @AfterEach
    void tearDown() {
        try {
            StompTestCleanup.closeAll(stompClient, taskScheduler, openSessions.toArray(new StompSession[0]));
            // 연결 종료로 트리거된 비동기 endByConnection()이 끝나기를 기다린다. 이 테스트가
            // 실제로 확인하는 시청 상태 기준(Redis presence)과 동일한 기준으로 완료를 판단한다 -
            // 그렇지 않으면 뒤늦은 DB 삽입/삭제가 아래 deleteAll()과 경합해 FK 위반이 날 수 있다.
            await().atMost(3, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> createdWatcherIds.forEach(
                    id -> assertThat(stringRedisTemplate.hasKey(presenceKey(id))).isFalse()));
        } catch (Exception ignored) {
            // 정리 단계의 실패로 원래 테스트 실패 원인을 가리지 않는다.
        } finally {
            snapshotRepository.deleteAll();
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

    /** 새 사용자를 만들고, tearDown에서 presence 소멸을 확인할 목록에 등록한다. */
    private UUID createWatcher(String emailPrefix) {
        User user = userRepository.save(User.builder()
            .email(emailPrefix + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("테스트유저")
            .role(UserRole.USER)
            .locked(false)
            .build());
        createdWatcherIds.add(user.getId());
        return user.getId();
    }

    /** 새 STOMP 연결을 열고, tearDown에서 일괄 정리할 목록에 등록한다. */
    private StompSession connectAs(UUID userId, CompletableFuture<String> errorFuture) throws Exception {
        String token = "valid-token-" + userId;
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            userId.toString(), null, List.of());
        when(jwtProvider.validate(token)).thenReturn(true);
        when(jwtProvider.getAuthentication(token)).thenReturn(authentication);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = stompClient
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

        openSessions.add(session);
        return session;
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

    private StompFrameHandler noopFrameHandler() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
            }
        };
    }

    private String presenceKey(UUID watcherId) {
        return "mopl:presence:watcher:" + watcherId;
    }

    /**
     * ContentChatService의 시청 검증(isWatching)은 DB가 아니라 Redis presence를 본다
     * (WatchingSessionPresenceReader). DB upsert와 Redis swap은 하나의 트랜잭션이 아니므로,
     * 실제 채팅 SEND 통과 조건인 이 Redis 값을 직접 확인해야 한다.
     */
    private void awaitWatching(UUID watcherId, UUID expectedContentId) {
        await().atMost(8, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                Map<Object, Object> presence = stringRedisTemplate.opsForHash().entries(presenceKey(watcherId));
                assertThat(presence).isNotEmpty();
                assertThat(presence.get("contentId")).isEqualTo(expectedContentId.toString());
            });
    }

    @Test
    @DisplayName("[E2E] 상한(3개)을 넘는 네 번째 chat 구독은 등록되지 않아 해당 콘텐츠 채팅 메시지를 받지 못한다")
    void fourthChatSubscription_neverReceivesBroadcast_whenExceedingLimit() throws Exception {
        StompSession session = connectAs(watcherId, null);
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

        UUID senderId = createWatcher("chat-sub-limit-sender-");
        StompSession senderSession = connectAs(senderId, null);
        senderSession.subscribe("/sub/contents/" + content4 + "/watch", noopFrameHandler());
        awaitWatching(senderId, content4);

        senderSession.send("/pub/contents/" + content4 + "/chat",
            new ContentChatSendRequest("도달하면 안 되는 메시지"));

        await().during(2, TimeUnit.SECONDS)
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(fourth.isDone()).isFalse());
    }

    @Test
    @DisplayName("[E2E] 상한(3개)을 넘는 네 번째 chat 구독은 등록되지 않고, ERROR 프레임 없이 "
        + "연결·기존 구독은 그대로 유지되며, 상한 이내였던 첫 구독은 계속 정상 수신한다")
    void fourthChatSubscription_droppedSilently_whileExistingSubscriptionsStillWork() throws Exception {
        CompletableFuture<String> errorReceived = new CompletableFuture<>();
        StompSession session = connectAs(watcherId, errorReceived);

        UUID content1 = createContent("채팅상한1");
        UUID content2 = createContent("채팅상한2");
        UUID content3 = createContent("채팅상한3");
        UUID content4 = createContent("채팅상한4");

        CompletableFuture<ContentChatDto> first = subscribeChatAndCapture(session, content1).future();
        subscribeChatAndCapture(session, content2);
        subscribeChatAndCapture(session, content3);
        Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);

        CompletableFuture<ContentChatDto> fourth = subscribeChatAndCapture(session, content4).future();
        Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);

        // 콘텐츠마다 별도 sender 유저·연결을 쓴다. 같은 연결에서 watch를 갈아타면(swap),
        // 이전 콘텐츠 SEND(presence 확인)와 다음 콘텐츠 SUBSCRIBE(presence 갱신)가 서로 다른
        // 스레드에서 동시에 처리될 때 순서가 뒤집히는 레이스가 있다.
        UUID senderForContent4 = createWatcher("chat-sub-limit-sender4-");
        StompSession senderSession4 = connectAs(senderForContent4, null);
        senderSession4.subscribe("/sub/contents/" + content4 + "/watch", noopFrameHandler());
        awaitWatching(senderForContent4, content4);
        senderSession4.send("/pub/contents/" + content4 + "/chat",
            new ContentChatSendRequest("도달하면 안 되는 메시지"));

        UUID senderForContent1 = createWatcher("chat-sub-limit-sender1-");
        StompSession senderSession1 = connectAs(senderForContent1, null);
        senderSession1.subscribe("/sub/contents/" + content1 + "/watch", noopFrameHandler());
        awaitWatching(senderForContent1, content1);
        senderSession1.send("/pub/contents/" + content1 + "/chat",
            new ContentChatSendRequest("정상 수신 메시지"));

        await().during(2, TimeUnit.SECONDS)
            .atMost(3, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(fourth.isDone()).isFalse());

        assertThat(errorReceived.isDone()).isFalse();
        assertThat(session.isConnected()).isTrue();
        assertThat(first.get(3, TimeUnit.SECONDS).content()).isEqualTo("정상 수신 메시지");
    }

    @Test
    @DisplayName("[E2E] 상한(3) 이내에서 콘텐츠를 순차 이동(구독→해제→재구독)하며 시청해도 "
        + "매번 정상적으로 메시지를 수신한다 (오탐 없음)")
    void sequentiallyMovingAcrossContents_neverBlocked_evenBeyondLimitCountOverTime() throws Exception {
        StompSession session = connectAs(watcherId, null);
        UUID senderId = createWatcher("chat-sub-limit-sequential-sender-");
        StompSession senderSession = connectAs(senderId, null);

        for (int i = 0; i < 5; i++) {
            UUID contentId = createContent("순차이동콘텐츠" + i);

            // sender가 이 콘텐츠를 실제로 시청 중이어야 채팅 SEND가 통과한다
            // (ContentChatService의 isWatching 검증). watch SUBSCRIBE는 연결당 활성 세션이
            // 1개로 swap되므로, 반복마다 다음 콘텐츠로 갈아타는 것이 정상 시청 흐름이다.
            //
            // 이 루프는 매 반복 끝에서 future.get()으로 메시지 수신을 blocking 대기하므로,
            // 다음 콘텐츠로 넘어가기 전에 이전 SEND의 서버 처리가 반드시 끝났음이 보장된다
            senderSession.subscribe("/sub/contents/" + contentId + "/watch", noopFrameHandler());
            awaitWatching(senderId, contentId);

            ChatSubscription chatSub = subscribeChatAndCapture(session, contentId);
            Thread.sleep(SUBSCRIBE_SETTLE_MILLIS); // 구독 안착 시간 확보

            senderSession.send("/pub/contents/" + contentId + "/chat",
                new ContentChatSendRequest("순차이동 메시지 " + i));

            ContentChatDto dto = chatSub.future().get(3, TimeUnit.SECONDS);
            assertThat(dto).isNotNull();

            chatSub.subscription().unsubscribe();
            Thread.sleep(100); // UNSUBSCRIBE 처리(chat 구독 카운트 감소)를 서버가 반영할 시간
        }
    }
}
