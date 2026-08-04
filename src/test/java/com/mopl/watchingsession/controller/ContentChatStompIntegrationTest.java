package com.mopl.watchingsession.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
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
 * 콘텐츠 실시간 채팅 STOMP 파이프라인 통합 테스트
 *
 * 목적: ContentChatController의 @Valid가 실제 STOMP 메시징 파이프라인에서
 * 동작하는지 확인한다. 컨트롤러 메서드 직접 호출(ContentChatControllerTest)로는
 * Bean Validation이 트리거되지 않으므로, 실제 WebSocket 클라이언트로 SEND해서
 * 검증 실패 시 브로드캐스트가 발생하지 않는지 end-to-end로 확인해야만 한다.
 *
 * StompAuthIntegrationTest와 동일한 실제 WS 클라이언트 설정을 재사용한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ContentChatStompIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

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

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;
    private StompSession session;

    private UUID senderId;
    private UUID contentId;

    @BeforeEach
    void setUp() {
        taskScheduler = createTaskScheduler();
        stompClient = createNativeStompClient();

        User sender = userRepository.save(User.builder()
            .email("chat-integration-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("우디")
            .role(UserRole.USER)
            .locked(false)
            .build());
        senderId = sender.getId();

        Content content = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title("통합 테스트 콘텐츠")
            .description("설명")
            .build());
        contentId = content.getId();

        snapshotRepository.save(WatchingSessionSnapshot.builder()
            .watcherId(senderId)
            .contentId(contentId)
            .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
            .build());
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

    private WebSocketStompClient createNativeStompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setTaskScheduler(taskScheduler);
        client.setDefaultHeartbeat(CLIENT_HEARTBEAT);
        return client;
    }

    private ThreadPoolTaskScheduler createTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chat-stomp-test-client-");
        scheduler.initialize();
        return scheduler;
    }

    private String wsUrl() {
        return "ws://localhost:" + port + "/ws/websocket";
    }

    private StompSession connectAs(UUID userId) throws Exception {
        String token = "valid-token-" + userId;
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            userId.toString(), null, List.of());
        when(jwtProvider.validate(token)).thenReturn(true);
        when(jwtProvider.getAuthentication(token)).thenReturn(authentication);

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        return stompClient
            .connectAsync(wsUrl(), (WebSocketHttpHeaders) null, connectHeaders, new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("정상 content로 SEND하면 구독자에게 ContentChatDto가 브로드캐스트됨")
    void sendChat_validContent_broadcastsToSubscribers() throws Exception {
        // given
        session = connectAs(senderId);
        String destination = "/sub/contents/" + contentId + "/chat";

        CompletableFuture<ContentChatDto> received = new CompletableFuture<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ContentChatDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                received.complete((ContentChatDto) payload);
            }
        });

        // 구독 등록 대기 후 SEND
        Thread.sleep(1000);

        // when
        session.send("/pub/contents/" + contentId + "/chat", Map.of("content", "안녕하세요"));

        // then
        ContentChatDto dto = received.get(5, TimeUnit.SECONDS);
        assertThat(dto.content()).isEqualTo("안녕하세요");
        assertThat(dto.sender().userId()).isEqualTo(senderId);
    }

    @Test
    @DisplayName("content가 500자를 초과하면 Bean Validation에 걸려 구독자에게 브로드캐스트되지 않는다")
    void sendChat_contentOver500Chars_doesNotBroadcast() throws Exception {
        // given
        session = connectAs(senderId);
        String destination = "/sub/contents/" + contentId + "/chat";

        CompletableFuture<ContentChatDto> received = new CompletableFuture<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ContentChatDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                received.complete((ContentChatDto) payload);
            }
        });

        Thread.sleep(1000);

        String tooLong = "가".repeat(501);

        // when: 검증 실패를 유발하는 SEND
        session.send("/pub/contents/" + contentId + "/chat", Map.of("content", tooLong));

        // then: 정상 브로드캐스트가 오지 않아야 함 (짧은 대기 후 미완료 확인)
        assertThatThrownBy(() -> received.get(2, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("content가 빈 문자열이면 Bean Validation에 걸려 구독자에게 브로드캐스트되지 않는다")
    void sendChat_blankContent_doesNotBroadcast() throws Exception {
        // given
        session = connectAs(senderId);
        String destination = "/sub/contents/" + contentId + "/chat";

        CompletableFuture<ContentChatDto> received = new CompletableFuture<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return ContentChatDto.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                received.complete((ContentChatDto) payload);
            }
        });

        Thread.sleep(1000);

        // when
        session.send("/pub/contents/" + contentId + "/chat", Map.of("content", "   "));

        // then
        assertThatThrownBy(() -> received.get(2, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);
    }
}
