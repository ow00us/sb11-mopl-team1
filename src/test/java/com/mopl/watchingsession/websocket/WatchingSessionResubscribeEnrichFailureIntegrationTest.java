package com.mopl.watchingsession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doReturn;
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
import com.mopl.watchingsession.dto.ChangeType;
import com.mopl.watchingsession.dto.WatchingSessionChange;
import com.mopl.watchingsession.presence.WatchingPresence;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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
import org.springframework.data.redis.core.RedisTemplate;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WatchingSessionResubscribeEnrichFailureIntegrationTest {

    private static final long[] CLIENT_HEARTBEAT = {4000, 4000};
    private static final long SETTLE_MILLIS = 300;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
        .withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtProvider jwtProvider;

    // enrich() 단계(userRepository.findById)만 특정 시점에 실패시키기 위해 spy로 감쌈
    @MockitoSpyBean
    private UserRepository userRepository;

    @Autowired
    private ContentRepository contentRepository;
    @Autowired
    private WatchingSessionSnapshotRepository snapshotRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;
    private StompSession session;
    private StompSession observerSession;
    private UUID watcherId;
    private UUID contentAId;
    private UUID contentBId;

    @BeforeEach
    void setUp() {
        taskScheduler = createTaskScheduler();
        stompClient = createNativeStompClient();

        User watcher = userRepository.save(User.builder()
            .email("e2e-regression-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("회귀테스트유저")
            .role(UserRole.USER)
            .locked(false)
            .build());
        watcherId = watcher.getId();

        Content contentA = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title("E2E 회귀 테스트 콘텐츠A")
            .description("설명A")
            .build());
        contentAId = contentA.getId();

        Content contentB = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title("E2E 회귀 테스트 콘텐츠B")
            .description("설명B")
            .build());
        contentBId = contentB.getId();
    }

    @AfterEach
    void tearDown() {
        disconnectQuietly(session);
        disconnectQuietly(observerSession);

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
        scheduler.setThreadNamePrefix("watch-e2e-regression-client-");
        scheduler.initialize();
        return scheduler;
    }

    private void disconnectQuietly(StompSession target) {
        if (target != null && target.isConnected()) {
            try {
                target.disconnect();
            } catch (MessageDeliveryException ignored) {
                // ERROR 프레임 처리 직후 서버가 먼저 연결을 닫을 수 있습니다.
            }
        }
    }

    private String presenceKey(UUID watcherId) {
        return "mopl:presence:watcher:" + watcherId;
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

    @Test
    @DisplayName("[회귀] A 시청 중 B로 재구독하다 enrich에서 실패해 보상 삭제되면, A를 보던 다른 관찰자에게 LEAVE가 브로드캐스트된다")
    void resubscribeEnrichFailure_broadcastsLeaveToContentAObserver() throws Exception {
        // given: 관찰자가 콘텐츠 A의 watch 토픽을 미리 구독해 LEAVE 수신 대기
        User observer = userRepository.save(User.builder()
            .email("resubscribe-fail-observer-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash").name("관찰자").role(UserRole.USER).locked(false).build());
        observerSession = connectAs(observer.getId(), null);

        String watchADestination = "/sub/contents/" + contentAId + "/watch";
        String watchBDestination = "/sub/contents/" + contentBId + "/watch";

        CompletableFuture<WatchingSessionChange> leaveOnA = new CompletableFuture<>();
        observerSession.subscribe(watchADestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers)
            {
                return WatchingSessionChange.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                WatchingSessionChange change = (WatchingSessionChange) payload;
                if (change.type() == ChangeType.LEAVE) leaveOnA.complete(change);
            }
        });
        Thread.sleep(SETTLE_MILLIS);

        // 시청자가 먼저 콘텐츠 A를 정상 구독 (sub-1)
        CompletableFuture<String> errorReceived = new CompletableFuture<>();
        session = connectAs(watcherId, errorReceived);
        session.subscribe(watchADestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return WatchingSessionChange.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) { }
        });

        await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(snapshotRepository.findByWatcherId(watcherId)).isPresent());

        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> {
                WatchingPresence presenceOnA =
                    (WatchingPresence) redisTemplate.opsForValue().get(presenceKey(watcherId));
                assertThat(presenceOnA).isNotNull();
                assertThat(presenceOnA.contentId()).isEqualTo(contentAId);
            });

        // when: 콘텐츠 B로 재구독. enrich(userRepository.findById)만 실패하도록 유도
        User realWatcher = userRepository.findById(watcherId).orElseThrow();
        doReturn(Optional.of(realWatcher))  // 1번째 호출: previous(A) 조회용 enrich - 성공
            .doReturn(Optional.empty())     // 2번째 호출: 새 스냅샷(B) enrich - 실패 유도
            .when(userRepository).findById(watcherId);

        session.subscribe(watchBDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) { }
        });

        // then: 시청자 본인은 ERROR 프레임 수신
        String errorPayload = errorReceived.get(5, TimeUnit.SECONDS);
        assertThat(errorPayload).contains(ErrorCode.RESOURCE_NOT_FOUND.getCode());

        // then: 보상 삭제로 DB 세션 소멸 + A를 보던 관찰자는 LEAVE 수신
        await().atMost(5, TimeUnit.SECONDS).pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty());

        // 보상삭제로 presence도 함께 삭제는지 확인
        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() ->
                assertThat(redisTemplate.opsForValue().get(presenceKey(watcherId))).isNull());

        WatchingSessionChange leaveChange = leaveOnA.get(5, TimeUnit.SECONDS);
        assertThat(leaveChange.watchingSessionDto().watcher().userId()).isEqualTo(watcherId);
        assertThat(leaveChange.watchingSessionDto().content().id()).isEqualTo(contentAId);
    }
}
