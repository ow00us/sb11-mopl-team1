package com.mopl.watchingsession.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import com.mopl.support.websocket.StompTestCleanup;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
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
 * 콘텐츠 실시간 채팅 STOMP 파이프라인 통합 테스트
 *
 * 목적: ContentChatController의 @Valid가 실제 STOMP 메시징 파이프라인에서
 * 동작하는지 확인한다. 컨트롤러 메서드 직접 호출(ContentChatControllerTest)로는
 * Bean Validation이 트리거되지 않으므로, 실제 WebSocket 클라이언트로 SEND해서
 * 검증 실패 시 브로드캐스트가 발생하지 않는지 end-to-end로 확인해야만 한다.
 *
 * StompAuthIntegrationTest와 동일한 실제 WS 클라이언트 설정을 재사용한다.
 *
 * 구독 등록 확인은 RECEIPT가 아닌 짧은 대기(Thread.sleep)로 처리한다.
 * enableSimpleBroker(인메모리 심플 브로커) 환경에서는 STOMP RECEIPT가
 * 자동으로 오지 않아(setAutoReceipt + addReceiptTask 방식이 계속 타임아웃) 결정적 방법을 쓸 수 없기 때문이다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public class ContentChatStompIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

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
                    return String.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    if (errorFuture != null && payload != null) {
                        errorFuture.complete((String) payload);
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

    // 채팅 destination을 구독하고, 구독이 브로커에 반영될 시간을 확보한 뒤 수신용 Future를 반환한다.
    private CompletableFuture<ContentChatDto> subscribeAndWait(StompSession session, String destination)
        throws InterruptedException {
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

        Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);

        return received;
    }

    @Test
    @DisplayName("정상 content로 SEND하면 구독자에게 ContentChatDto가 브로드캐스트됨")
    void sendChat_validContent_broadcastsToSubscribers() throws Exception {
        // given
        session = connectAs(senderId, null);
        String destination = "/sub/contents/" + contentId + "/chat";
        CompletableFuture<ContentChatDto> received = subscribeAndWait(session, destination);

        // when
        session.send("/pub/contents/" + contentId + "/chat", Map.of("content", "안녕하세요"));

        // then
        ContentChatDto dto = received.get(5, TimeUnit.SECONDS);
        assertThat(dto.content()).isEqualTo("안녕하세요");
        assertThat(dto.sender().userId()).isEqualTo(senderId);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠 ID로 SEND 시 404 CONTENT_NOT_FOUND STOMP ERROR 프레임을 반환")
    void sendChat_invalidContentId_returnsErrorFrame() throws Exception {
        // given
        CompletableFuture<String> errorReceived = new CompletableFuture<>();
        session = connectAs(senderId, errorReceived);

        // UUID 형식은 맞지만 DB에 없는 임의의 콘텐츠 ID
        UUID nonExistentContentId = UUID.randomUUID();

        // when (구독 없이 바로 송신)
        session.send("/pub/contents/" + nonExistentContentId + "/chat", Map.of("content", "도배 시도"));

        // then: 에러 프레임이 수신되었는지 확인 (서비스 로직의 CONTENT_NOT_FOUND)
        String errorPayload = errorReceived.get(5, TimeUnit.SECONDS);
        assertThat(errorPayload).contains(ErrorCode.CONTENT_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("content가 500자를 초과하면 @Valid에 걸려 400 INVALID_INPUT STOMP ERROR 프레임을 반환하고 구독자에게 브로드캐스트되지 않는다")
    void sendChat_contentOver500Chars_doesNotBroadcast() throws Exception {
        // given
        CompletableFuture<String> errorReceived = new CompletableFuture<>();
        session = connectAs(senderId, errorReceived);
        String destination = "/sub/contents/" + contentId + "/chat";
        CompletableFuture<ContentChatDto> received = subscribeAndWait(session, destination);

        String tooLong = "가".repeat(501);

        // when
        session.send("/pub/contents/" + contentId + "/chat", Map.of("content", tooLong));

        // then: INVALID_INPUT 코드 및 content 필드 에러 상세 내용 확인
        String errorPayload = errorReceived.get(5, TimeUnit.SECONDS);
        assertThat(errorPayload).contains(ErrorCode.INVALID_INPUT.getCode());
        assertThat(errorPayload).contains("\"content\""); // details 안에 필드 에러가 담기는지 검증

        // then: 정상 브로드캐스트는 별도 구독 Future로 확인 - 오지 않아야 함
        assertThatThrownBy(() -> received.get(2, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("content가 빈 문자열이면 @Valid에 걸려 INVALID_INPUT STOMP ERROR 프레임을 반환하고 구독자에게 브로드캐스트되지 않는다")
    void sendChat_blankContent_doesNotBroadcast() throws Exception {
        // given
        CompletableFuture<String> errorReceived = new CompletableFuture<>();
        session = connectAs(senderId, errorReceived);
        String destination = "/sub/contents/" + contentId + "/chat";
        CompletableFuture<ContentChatDto> received = subscribeAndWait(session, destination);

        // when
        session.send("/pub/contents/" + contentId + "/chat", Map.of("content", "   "));

        // then: INVALID_INPUT 코드 확인
        String errorPayload = errorReceived.get(5, TimeUnit.SECONDS);
        assertThat(errorPayload).contains(ErrorCode.INVALID_INPUT.getCode());
        assertThat(errorPayload).contains("\"content\"");

        // then: 정상 브로드캐스트는 별도 구독 Future로 확인 - 오지 않아야 함
        assertThatThrownBy(() -> received.get(2, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);
    }

    @Test
    @DisplayName("잘못된 contentId로 SEND 시 ERROR 프레임은 발신자 세션에만 전달되고 다른 세션에는 전달되지 않음")
    void sendChat_invalidContentId_errorFrameIsolatedToSenderSession() throws Exception {
        // given: 발신자 세션과 아무 요청도 보내지 않는 관찰자 세션을 별도로 연결
        CompletableFuture<String> senderErrorReceived = new CompletableFuture<>();
        session = connectAs(senderId, senderErrorReceived);

        User observer = userRepository.save(User.builder()
            .email("chat-observer-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("관찰자")
            .role(UserRole.USER)
            .locked(false)
            .build());
        CompletableFuture<String> observerErrorReceived = new CompletableFuture<>();
        StompSession observerSession = connectAs(observer.getId(), observerErrorReceived);

        UUID nonExistentContentId = UUID.randomUUID();

        try {
            // when: 발신자만 잘못된 contentId로 SEND
            session.send("/pub/contents/" + nonExistentContentId + "/chat", Map.of("content", "도배 시도"));

            // then: 발신자 세션에는 ERROR 프레임이 도착
            String senderPayload = senderErrorReceived.get(5, TimeUnit.SECONDS);
            assertThat(senderPayload).contains(ErrorCode.CONTENT_NOT_FOUND.getCode());

            // then: 관찰자 세션에는 아무것도 도착하지 않아야 함
            assertThatThrownBy(() -> observerErrorReceived.get(2, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);
        } finally {
            /*
             * 테스트 도중 서버가 observer 세션을 먼저 종료했더라도
             * 정리 과정이 본래의 테스트 결과를 덮어쓰지 않도록 안전하게 종료한다.
             */
            StompTestCleanup.disconnectQuietly(observerSession);
        }
    }
}
