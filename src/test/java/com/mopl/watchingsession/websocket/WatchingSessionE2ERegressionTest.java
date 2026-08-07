package com.mopl.watchingsession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
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
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.dto.WatchingSessionChange;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;


/**
 * 시청 세션 전체 흐름 E2E 회귀 테스트.
 *
 * 목적: 개별 리스너 단위 테스트(WatchingSessionSubscribeListenerTest 등)는 Mock 기반이라
 * "실제 STOMP 파이프라인을 통해 입장→채팅→퇴장→에러 응답이 이어지는 전체 흐름"이
 * 깨지지 않는지는 검증하지 못한다. 특히 이번 E-05 작업(StompErrorFrameSender 도입,
 * @ EventListener(SessionSubscribeEvent) 경로의 에러 프레임 전달)이 기존 E-03(입장),
 * E-04(같이보기 채팅) 흐름을 회귀시키지 않는지 확인하는 것이 이 테스트의 목적이다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WatchingSessionE2ERegressionTest {

    private record SubscriptionResult<T>(Subscription subscription, CompletableFuture<T> future) {}

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static final long[] CLIENT_HEARTBEAT = {4000, 4000};
    private static final long SETTLE_MILLIS = 300;

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

    private UUID watcherId;
    private UUID contentId;

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

        Content content = contentRepository.save(Content.builder()
            .type(ContentType.MOVIE)
            .title("E2E 회귀 테스트 콘텐츠")
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
                // ERROR 프레임 처리 직후 서버가 먼저 연결을 닫을 수 있습니다.
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

    private <T> SubscriptionResult<T> subscribeAndWait(StompSession session, String destination, Class<T> payloadType)
        throws InterruptedException {

        CompletableFuture<T> received = new CompletableFuture<>();
        Subscription subscription = session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return payloadType;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                received.complete(payloadType.cast(payload));
            }
        });
        Thread.sleep(SETTLE_MILLIS);
        return new SubscriptionResult<>(subscription, received);
    }

    @Test
    @DisplayName("[E2E 회귀] 입장(SUBSCRIBE) → 채팅(SEND) → 정상 퇴장(UNSUBSCRIBE) 전체 흐름이 정상 동작")
    void fullHappyPath_joinChatLeave_worksEndToEnd() throws Exception {
        // given: watch 토픽 구독(입장) 전에 watch 채널 브로드캐스트를 관찰할 별도 구독 세션 준비
        session = connectAs(watcherId, null);
        String watchDestination = "/sub/contents/" + contentId + "/watch";
        String chatDestination = "/sub/contents/" + contentId + "/chat";

        SubscriptionResult<WatchingSessionChange> joinResult =
            subscribeAndWait(session, watchDestination, WatchingSessionChange.class);

        // when 1: 입장 - watch 토픽 구독
        // (subscribeAndWait 안에서 이미 구독이 이뤄짐 - SessionSubscribeEvent 발생)

        // then 1: JOIN 브로드캐스트 수신 확인
        WatchingSessionChange joinChange = joinResult.future.get(5, TimeUnit.SECONDS);
        assertThat(joinChange.watchingSessionDto().watcher().userId()).isEqualTo(watcherId);

        // when 2: 채팅 전송
        SubscriptionResult<ContentChatDto> chatResult =
            subscribeAndWait(session, chatDestination, ContentChatDto.class);
        session.send("/pub/contents/" + contentId + "/chat", Map.of("content", "안녕하세요"));

        // then 2: 채팅 브로드캐스트 수신 확인
        ContentChatDto chatDto = chatResult.future.get(5, TimeUnit.SECONDS);
        assertThat(chatDto.content()).isEqualTo("안녕하세요");

        // when 3: 정상 퇴장 - watch 구독 해제(UNSUBSCRIBE)
        joinResult.subscription().unsubscribe();

        // then 3: 세션이 실제로 DB에서 삭제되었는지로 정상 퇴장을 확인 (구독 해제 후 세션 없어야 함)
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                    .untilAsserted(() ->
                        assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty());
    }

    @Test
    @DisplayName("[E2E 회귀] 존재하지 않는 콘텐츠 구독(비정상 입장) 시 STOMP ERROR 프레임이 발신자에게 전달되고, 연결도 종료됨")
    void invalidContentSubscribe_returnsErrorFrame_toSubscriberOnly() throws Exception {
        // given
        CompletableFuture<String> errorReceived = new CompletableFuture<>();
        session = connectAs(watcherId, errorReceived);

        UUID nonExistentContentId = UUID.randomUUID();
        String invalidWatchDestination = "/sub/contents/" + nonExistentContentId + "/watch";

        // when: 존재하지 않는 콘텐츠의 watch 토픽을 구독 (SUBSCRIBE 시점 실패 유도)
        session.subscribe(invalidWatchDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                // 정상 프레임은 오지 않아야 함
            }
        });

        // then: ERROR 프레임이 CONTENT_NOT_FOUND로 전달됨
        String errorPayload = errorReceived.get(5, TimeUnit.SECONDS);
        assertThat(errorPayload).contains(ErrorCode.CONTENT_NOT_FOUND.getCode());

        // WatchingSessionSubscribeExistenceInterceptor.preSend()가 브로커 등록을 막기 위해
        // errorFrameSender.send() 후 null을 반환하면, StompSubProtocolHandler.handleError()가
        // ERROR 처리 경로를 타면서 finally에서 항상 session.close(PROTOCOL_ERROR)를 호출 -> 연결 종료됨
        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(() -> !session.isConnected());

        // 시청 세션이 DB에 생성되지 않았어야 함
        assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty();
    }

    @Test
    @DisplayName("[E2E 회귀] 비정상 입장(에러) 후 연결이 종료되어도 재연결하면 정상 콘텐츠는 입장 가능")
    void afterErrorOnInvalidContent_canStillJoinValidContent() throws Exception {
        // given: 먼저 잘못된 콘텐츠로 구독 시도해 에러를 한 번 겪음
        CompletableFuture<String> errorReceived = new CompletableFuture<>();
        StompSession firstSession = connectAs(watcherId, errorReceived);

        try {
            UUID nonExistentContentId = UUID.randomUUID();
            firstSession.subscribe("/sub/contents/" + nonExistentContentId + "/watch", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                }
            });

            await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> !firstSession.isConnected());
        } finally {
            if (firstSession.isConnected()) {
                firstSession.disconnect();
            }
        }

        // when: 재연결 후 실제로 존재하는 콘텐츠를 구독 (FE의 자동 재연결에 해당)
        session = connectAs(watcherId, null);
        SubscriptionResult<WatchingSessionChange> joinResult =
            subscribeAndWait(session, "/sub/contents/" + contentId + "/watch", WatchingSessionChange.class);

        // then: 정상적으로 JOIN 처리됨 - 이전 에러/연결 종료가 이후 재연결 흐름을 오염시키지 않음
        WatchingSessionChange joinChange = joinResult.future().get(5, TimeUnit.SECONDS);
        assertThat(joinChange.watchingSessionDto().watcher().userId()).isEqualTo(watcherId);
        assertThat(snapshotRepository.findByWatcherId(watcherId)).isPresent();
    }

    @Test
    @DisplayName("[E2E 회귀] 비정상 입장 에러 프레임은 발신자 세션에만 전달되고 다른 관찰자에게는 전달되지 않음")
    void invalidContentSubscribe_errorFrameIsolatedToSubscriberSession() throws Exception {
        // given: 실패를 유발할 세션과, 아무 요청도 하지 않는 관찰자 세션
        CompletableFuture<String> subscriberErrorReceived = new CompletableFuture<>();
        session = connectAs(watcherId, subscriberErrorReceived);

        User observer = userRepository.save(User.builder()
            .email("e2e-observer-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("관찰자")
            .role(UserRole.USER)
            .locked(false)
            .build());
        CompletableFuture<String> observerErrorReceived = new CompletableFuture<>();
        StompSession observerSession = connectAs(observer.getId(), observerErrorReceived);

        UUID nonExistentContentId = UUID.randomUUID();

        try {
            // when: 발신자만 잘못된 콘텐츠 구독
            session.subscribe("/sub/contents/" + nonExistentContentId + "/watch", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return byte[].class;
                }

                @Override
                public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                }
            });

            // then: 발신자에게는 에러 프레임 도착
            String subscriberPayload = subscriberErrorReceived.get(5, TimeUnit.SECONDS);
            assertThat(subscriberPayload).contains(ErrorCode.CONTENT_NOT_FOUND.getCode());

            // then: 관찰자에게는 아무것도 전달되지 않음
            assertThatThrownBy(() -> observerErrorReceived.get(2, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);
        } finally {
            if (observerSession.isConnected()) {
                observerSession.disconnect();
            }
        }
    }

    @Test
    @DisplayName("[E2E 회귀] 같은 연결에서 재구독한 뒤 이전 구독을 UNSUBSCRIBE해도 현재 시청 세션은 유지되고, 현재 구독을 UNSUBSCRIBE해야만 실제로 종료됨")
    void resubscribeSameConnection_staleUnsubscribeIsNoop_onlyCurrentUnsubscribeEndsSession() throws Exception {
        // given: watch 토픽을 구독(입장)해 첫 번째 시청 세션(sub-1)을 시작
        session = connectAs(watcherId, null);
        String watchDestination = "/sub/contents/" + contentId + "/watch";

        AtomicInteger leaveCount = new AtomicInteger(0);

        // 브로드캐스트 관찰 전용 구독. 테스트가 끝날 때까지 unsubscribe하지 않는다.
        // sub-1, sub-2의 UNSUBSCRIBE와는 무관하게 이 구독으로만 LEAVE 수신 여부를 판단해야
        // "구독 해제 직후 그 구독 자신으로 메시지를 받으려는" 레이스를 피할 수 있다.
        session.subscribe(watchDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return WatchingSessionChange.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                WatchingSessionChange change = (WatchingSessionChange) payload;
                if (change.type() == ChangeType.LEAVE) {
                    leaveCount.incrementAndGet();
                }
            }
        });
        Thread.sleep(SETTLE_MILLIS);

        // 실제 입장을 유발할 첫 번째 구독(sub-1)
        Subscription firstSubscription = session.subscribe(watchDestination, noopHandler());
        Thread.sleep(SETTLE_MILLIS);

        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(snapshotRepository.findByWatcherId(watcherId)).isPresent());

        // 같은 연결에서 같은 콘텐츠를 다시 구독(sub-2). sub-1은 이 시점부터 낡은 구독이 됨
        Subscription currentSubscription = session.subscribe(watchDestination, noopHandler());
        Thread.sleep(SETTLE_MILLIS);

        // when: 이전 구독(sub-1)을 UNSUBSCRIBE - 낡은 구독의 늦은 정리 시도를 재현
        firstSubscription.unsubscribe();

        // then: 정의된 배출 대기 구간(SETTLE_MILLIS) 동안 leaveCount가 계속 0으로 유지되는지 확인
        // 단발성 sleep+assert가 아닌 구간 내내 조건이 깨지지 않는지 검증
        // 낡은 구독의 UNSUBSCRIBE는 무동작이어야 하므로, 현재 시청 세션은 DB에 그대로 남아있어야 함
        await().during(SETTLE_MILLIS, TimeUnit.MILLISECONDS)
            .atMost(SETTLE_MILLIS + 2000, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(leaveCount.get()).isZero());
        assertThat(snapshotRepository.findByWatcherId(watcherId)).isPresent();

        // when: 현재 활성 구독(sub-2)을 UNSUBSCRIBE - 실제 퇴장
        currentSubscription.unsubscribe();

        // then: DB 삭제를 먼저 확정 대기한 뒤 같은 배출 경계까지 leaveCount가 정확히 1로 유지되는지 확인
        // DB삭제가 확인된 시점 이후에도 stale UNSUBSCRIBE로 인한 추가 LEAVE가 뒤늦게 도착하지 않는지 검증
        // 관찰자 구독(끊지 않고 유지 중)이 LEAVE를 정확히 한 번 수신해야 함
        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty());
        await().during(SETTLE_MILLIS, TimeUnit.MILLISECONDS)
            .atMost(SETTLE_MILLIS + 2000, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(leaveCount.get()).isEqualTo(1));
    }

    private StompFrameHandler noopHandler() {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return WatchingSessionChange.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                // 이 구독은 JOIN/LEAVE를 발생시키는 용도일 뿐, 수신 내용은 관찰자 구독에서 검증한다.
            }
        };
    }

    @Test
    @DisplayName("[E2E 회귀] 활성 watch 구독 상태에서 연결 자체가 끊기면(session.disconnect()), DB에서 세션이 삭제되고 다른 관찰자에게 LEAVE가 브로드캐스트")
    void disconnectWhileActivelyWatching_deletesSessionAndBroadcastsLeaveToObserver() throws Exception {
        // given: 시청자 세션이 watch 토픽을 구독해 활성 시청 세션을 시작한다.
        session = connectAs(watcherId, null);
        String watchDestination = "/sub/contents/" + contentId + "/watch";

        session.subscribe(watchDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return WatchingSessionChange.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
            }
        });
        Thread.sleep(SETTLE_MILLIS);

        await().atMost(5, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThat(snapshotRepository.findByWatcherId(watcherId)).isPresent());

        // 별도 관찰자(다른 유저)가 같은 콘텐츠의 watch 토픽을 구독해 LEAVE 수신 여부를 확인한다.
        User observer = userRepository.save(User.builder()
            .email("e2e-disconnect-observer-" + UUID.randomUUID() + "@test.com")
            .passwordHash("hash")
            .name("관찰자")
            .role(UserRole.USER)
            .locked(false)
            .build());
        StompSession observerSession = connectAs(observer.getId(), null);

        CompletableFuture<WatchingSessionChange> leaveReceived = new CompletableFuture<>();
        observerSession.subscribe(watchDestination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return WatchingSessionChange.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, @Nullable Object payload) {
                WatchingSessionChange change = (WatchingSessionChange) payload;
                if (change.type() == ChangeType.LEAVE) {
                    leaveReceived.complete(change);
                }
            }
        });
        Thread.sleep(SETTLE_MILLIS);

        try {
            // when: 시청자 세션이 정상 UNSUBSCRIBE 없이 연결 자체를 끊는다.
            session.disconnect();

            // then: DB에서 세션이 삭제되고, 관찰자에게 LEAVE가 브로드캐스트된다.
            await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(snapshotRepository.findByWatcherId(watcherId)).isEmpty());

            WatchingSessionChange leaveChange = leaveReceived.get(5, TimeUnit.SECONDS);
            assertThat(leaveChange.watchingSessionDto().watcher().userId()).isEqualTo(watcherId);
        } finally {
            if (observerSession.isConnected()) {
                observerSession.disconnect();
            }
        }
    }
}
