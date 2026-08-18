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
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * heartbeat와 TTL 만료의 실제 동작을 실제 Redis/DB로 검증하는 E2E.
 *
 * application-test.yml의 짧은 TTL 오버라이드(presence 2s / session 3s / heartbeat 500ms)에 의존한다.
 * 운영 값(90s/30m)을 그대로 쓰면 테스트가 분 단위로 늘어나 이 프로젝트의 다른 E2E와
 * 시간 스케일이 맞지 않는다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WatchingSessionHeartbeatE2ETest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
        .withExposedPorts(6379);

    private static final long[] CLIENT_HEARTBEAT = {4000, 4000};

    @LocalServerPort
    private int port;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WatchingSessionSnapshotRepository snapshotRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
            .email("e2e-heartbeat-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("heartbeat테스트유저")
            .role(UserRole.USER)
            .locked(false)
            .build());
        watcherId = watcher.getId();

        Content content = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title("heartbeat E2E 테스트 콘텐츠")
            .description("설명")
            .build());
        contentId = content.getId();
    }

    @AfterEach
    void tearDown() {
        try {
            StompTestCleanup.closeAll(stompClient, taskScheduler, session);
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
        scheduler.setThreadNamePrefix("heartbeat-e2e-client-");
        scheduler.initialize();
        return scheduler;
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
    @DisplayName("[E2E] heartbeat 없이 TTL이 지나면 Redis presence가 자동으로 사라진다")
    void withoutHeartbeat_presenceExpiresAfterTtl() throws Exception {
        // given: 시청 시작(SUBSCRIBE) - presence 키 생성 확인
        session = connectAs(watcherId, null);
        session.subscribe("/sub/contents/" + contentId + "/watch", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
            }
        });

        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() ->
                assertThat(stringRedisTemplate.hasKey(presenceKey(watcherId))).isTrue());

        // when: heartbeat를 한 번도 보내지 않고 TTL(2s)이 지나기를 기다린다.

        // then: presence-ttl(2s)이 지나면 Redis가 키를 자동으로 제거한다.
        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() ->
                assertThat(stringRedisTemplate.hasKey(presenceKey(watcherId))).isFalse());
    }

    @Test
    @DisplayName("[E2E] heartbeat를 주기적으로 보내면 presence와 DB expiresAt이 TTL 경과 후에도 유지된다")
    void withHeartbeat_presenceAndDbSessionSurviveBeyondTtl() throws Exception {
        // given: 시청 시작
        session = connectAs(watcherId, null);
        session.subscribe("/sub/contents/" + contentId + "/watch", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
            }
        });

        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(snapshotRepository.findByWatcherId(watcherId)).isPresent());

        Instant initialExpiresAt = snapshotRepository.findByWatcherId(watcherId).orElseThrow().getExpiresAt();

        // when: heartbeat-interval(500ms)보다 촘촘하게, presence-ttl(2s)을 훌쩍 넘는 3.5초 동안
        // heartbeat를 계속 보낸다.
        long deadline = System.currentTimeMillis() + 3500;
        while (System.currentTimeMillis() < deadline) {
            session.send("/pub/contents/" + contentId + "/watch/heartbeat", null);
            Thread.sleep(100);
        }

        // then: presence-ttl(2s)을 넘긴 시점인데도 presence가 살아있다.
        Map<Object, Object> presence = stringRedisTemplate.opsForHash().entries(presenceKey(watcherId));
        assertThat(presence).isNotEmpty();

        // then: DB expiresAt도 최초 값보다 뒤로 연장되어 있다 (heartbeat가 DB를 실제로 갱신했다는 증거).
        Instant renewedExpiresAt = snapshotRepository.findByWatcherId(watcherId).orElseThrow().getExpiresAt();
        assertThat(renewedExpiresAt).isAfter(initialExpiresAt);
    }

    @Test
    @DisplayName("[E2E] heartbeat를 멈추면 그 뒤로는 presence-ttl(2s) 안에 presence가 사라진다")
    void afterHeartbeatStops_presenceEventuallyExpires() throws Exception {
        // given: 시청 시작 후 짧게 heartbeat를 보내 살려둔다.
        session = connectAs(watcherId, null);
        session.subscribe("/sub/contents/" + contentId + "/watch", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
            }
        });

        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() ->
                assertThat(stringRedisTemplate.hasKey(presenceKey(watcherId))).isTrue());

        session.send("/pub/contents/" + contentId + "/watch/heartbeat", null);
        Thread.sleep(300);
        assertThat(stringRedisTemplate.hasKey(presenceKey(watcherId))).isTrue();

        // when: 이후로는 heartbeat를 전혀 보내지 않는다.

        // then: 마지막 heartbeat 이후 presence-ttl(2s)이 지나면 presence가 사라진다.
        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() ->
                assertThat(stringRedisTemplate.hasKey(presenceKey(watcherId))).isFalse());
    }
}
