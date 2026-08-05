package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.dto.DirectMessageSendRequest;
import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.directmessage.entity.ParticipantSlot;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.ConversationRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.global.security.JwtProvider;
import com.mopl.global.exception.ErrorResponse;
import com.mopl.global.common.CursorResponse;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Type;
import java.security.Principal;
import java.net.URI;
import java.util.ArrayList;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.messaging.MessageDeliveryException;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT
)
class DirectMessageStompIntegrationTest {

    // 실제 PostgreSQL에서 Repository와 트랜잭션을 검증한다.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>(
            "postgres:16"
        );

    private static final long[] CLIENT_HEARTBEAT = {
        4000,
        4000
    };

    // SUBSCRIBE 요청이 Simple Broker에 반영될 시간을 확보한다.
    private static final long SUBSCRIBE_SETTLE_MILLIS =
        300;

    @LocalServerPort
    private int port;

    // 실제 JWT 문자열을 만들지 않고 인증 결과만 테스트한다.
    @MockitoBean
    private JwtProvider jwtProvider;

    // 실제 Broadcaster를 사용하되, 실패 테스트에서만 예외를 설정한다.
    @MockitoSpyBean
    private DirectMessageBroadcaster broadcaster;

    @Autowired
    private DirectMessageWebSocketController controller;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationParticipantRepository
        participantRepository;

    @Autowired
    private DirectMessageRepository directMessageRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRestTemplate restTemplate;

    private WebSocketStompClient stompClient;
    private ThreadPoolTaskScheduler taskScheduler;

    // 이후 발신자와 수신자 세션을 모두 정리하기 위해 List로 관리한다.
    private final List<StompSession> sessions =
        new ArrayList<>();

    private UUID senderId;
    private UUID receiverId;
    private UUID conversationId;

    @BeforeEach
    void setUp() {
        // WebSocket 클라이언트의 heartbeat 작업을 실행할 스케줄러다.
        taskScheduler =
            createTaskScheduler();

        // 실제 서버에 연결할 테스트용 STOMP 클라이언트를 만든다.
        stompClient =
            createStompClient();

        // 테스트에 사용할 사용자 두 명을 실제 DB에 저장한다.
        User sender =
            userRepository.save(
                createUser("발신자")
            );

        User receiver =
            userRepository.save(
                createUser("수신자")
            );

        senderId = sender.getId();
        receiverId = receiver.getId();

        // 1:1 DM 대화방을 실제 DB에 저장한다.
        Conversation conversation =
            conversationRepository.save(
                Conversation.create()
            );

        conversationId =
            conversation.getId();

        // 저장된 대화에 발신자와 수신자를 참여자로 등록한다.
        participantRepository.saveAll(
            List.of(
                ConversationParticipant.create(
                    conversationId,
                    senderId,
                    ParticipantSlot.FIRST
                ),
                ConversationParticipant.create(
                    conversationId,
                    receiverId,
                    ParticipantSlot.SECOND
                )
            )
        );
    }

    @AfterEach
    void tearDown() {
        // 테스트에서 만든 모든 WebSocket 연결을 종료한다.
        sessions.forEach(session -> {
            if (session.isConnected()) {
                try {
                    session.disconnect();
                } catch (MessageDeliveryException ignored) {
                    // 서버가 먼저 연결을 닫은 경우는 무시한다.
                }
            }
        });

        if (stompClient != null) {
            stompClient.stop();
        }

        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }

        // 외래 키 관계의 반대 순서로 테스트 데이터를 삭제한다.
        directMessageRepository.deleteAll();
        participantRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();

        sessions.clear();
    }

    @Test
    @DisplayName("인증된 사용자가 WebSocket에 연결할 수 있다")
    void connect_authenticatedUser_success() throws Exception {
        // when
        StompSession session =
            connectAs(senderId);

        // then
        assertThat(session.isConnected())
            .isTrue();
    }

    private StompSession connectAs(
        UUID userId
    ) throws Exception {
        return connectAs(
            userId,
            null
        );
    }

    private StompSession connectAs(
        UUID userId,
        CompletableFuture<ErrorResponse> errorReceived
    ) throws Exception {
        // 사용자마다 구분되는 테스트용 토큰을 만든다.
        String token =
            "valid-token-" + userId;

        // STOMP 인증 Interceptor가 반환받을 인증 객체다.
        Authentication authentication =
            UsernamePasswordAuthenticationToken
                .authenticated(
                    userId.toString(),
                    null,
                    List.of()
                );

        when(jwtProvider.validate(token))
            .thenReturn(true);

        when(jwtProvider.getAuthentication(token))
            .thenReturn(authentication);

        // STOMP CONNECT 프레임에 Authorization 헤더를 넣는다.
        StompHeaders connectHeaders =
            new StompHeaders();

        connectHeaders.add(
            "Authorization",
            "Bearer " + token
        );

        StompSession session =
            stompClient
                .connectAsync(
                    webSocketUrl(),
                    (WebSocketHttpHeaders) null,
                    connectHeaders,
                    new StompSessionHandlerAdapter() {

                        @Override
                        public Type getPayloadType(
                            StompHeaders headers
                        ) {
                            // 서버가 보낸 STOMP ERROR JSON를
                            // ErrorResponse로 변환한다.
                            return ErrorResponse.class;
                        }

                        @Override
                        public void handleFrame(
                            StompHeaders headers,
                            Object payload
                        ) {
                            if (
                                errorReceived != null
                                && payload != null
                            ) {
                                errorReceived.complete(
                                    (ErrorResponse) payload
                                );
                            }
                        }
                    }
                )
                .get(
                    5,
                    TimeUnit.SECONDS
                );

        sessions.add(session);

        return session;
    }

    private CompletableFuture<DirectMessageDto>
    subscribeDirectMessages(
        StompSession session
    ) throws InterruptedException {

        CompletableFuture<DirectMessageDto> received =
            new CompletableFuture<>();

        String destination =
            "/sub/conversations/"
                + conversationId
                + "/direct-messages";

        session.subscribe(
            destination,
            new StompFrameHandler() {

                @Override
                public Type getPayloadType(
                    StompHeaders headers
                ) {
                    // 수신한 JSON을 DirectMessageDto로 변환한다.
                    return DirectMessageDto.class;
                }

                @Override
                public void handleFrame(
                    StompHeaders headers,
                    Object payload
                ) {
                    received.complete(
                        (DirectMessageDto) payload
                    );
                }
            }
        );

        // Simple Broker는 SUBSCRIBE RECEIPT를 자동으로 보내지 않으므로
        // 구독 정보가 등록될 짧은 시간을 확보한다.
        Thread.sleep(
            SUBSCRIBE_SETTLE_MILLIS
        );

        return received;
    }

    @Test
    @DisplayName("참여자가 보낸 DM을 저장하고 구독자에게 실시간으로 전달한다")
    void send_participant_savesAndBroadcasts()
        throws Exception {

        // given
        CompletableFuture<ErrorResponse> errorReceived =
            new CompletableFuture<>();

        StompSession senderSession =
            connectAs(
                senderId,
                errorReceived
            );

        StompSession receiverSession =
            connectAs(receiverId);

        CompletableFuture<DirectMessageDto> received =
            subscribeDirectMessages(
                receiverSession
            );

        DirectMessageSendRequest request =
            new DirectMessageSendRequest(
                "통합 테스트 메시지"
            );

        // when
        senderSession.send(
            "/pub/conversations/"
                + conversationId
                + "/direct-messages",
            request
        );

        // 정상 메시지와 STOMP ERROR 중 먼저 도착한 결과를 받는다.
        Object result =
            CompletableFuture
                .anyOf(
                    received,
                    errorReceived
                )
                .get(
                    5,
                    TimeUnit.SECONDS
                );

        // ErrorResponse가 왔다면 여기서 실제 오류 내용이 출력된다.
        assertThat(result)
            .as(
                "정상 DirectMessageDto 대신 STOMP ERROR가 수신되었습니다.: %s",
                result
            )
            .isInstanceOf(
                DirectMessageDto.class
            );

        DirectMessageDto response =
            (DirectMessageDto) result;

        // then
        assertThat(response.conversationId())
            .isEqualTo(conversationId);

        assertThat(response.sender().userId())
            .isEqualTo(senderId);

        assertThat(response.receiver().userId())
            .isEqualTo(receiverId);

        assertThat(response.content())
            .isEqualTo(
                "통합 테스트 메시지"
            );

        // WebSocket으로 받은 메시지가 실제 DB에도 저장되었는지 확인한다.
        var savedMessages =
            directMessageRepository.findAll();

        assertThat(savedMessages)
            .hasSize(1);

        assertThat(savedMessages.get(0).getConversationId())
            .isEqualTo(conversationId);

        assertThat(savedMessages.get(0).getSenderId())
            .isEqualTo(senderId);

        assertThat(savedMessages.get(0).getContent())
            .isEqualTo(
                "통합 테스트 메시지"
            );

        // 아직 수신자가 읽음 처리하지 않았으므로 null이다.
        assertThat(savedMessages.get(0).getReadAt())
            .isNull();
    }

    @Test
    @DisplayName("WebSocket 전송이 실패해도 커밋된 DM은 DB에 유지된다")
    void send_broadcastFailure_preservesSavedMessage() {
        // given
        DirectMessageSendRequest request =
            new DirectMessageSendRequest(
                "전송 실패 메시지"
            );

        Principal principal =
            () -> senderId.toString();

        // Broadcaster가 호출되면 WebSocket 전송 예외를 발생시킨다.
        doThrow(
            new MessageDeliveryException(
                "WebSocket 전송 실패"
            )
        ).when(broadcaster)
            .broadcast(
                eq(conversationId),
                any(DirectMessageDto.class)
            );

        // when & then
        assertThatThrownBy(() ->
            controller.send(
                conversationId,
                request,
                principal
            )
        ).isInstanceOf(
            MessageDeliveryException.class
        );

        // Controller에 예외가 발생했지만 Service 트랜잭션은
        // Broadcaster 호출 전에 이미 완료되었다.
        var savedMessages =
            directMessageRepository.findAll();

        assertThat(savedMessages)
            .hasSize(1);

        assertThat(savedMessages.get(0).getConversationId())
            .isEqualTo(conversationId);

        assertThat(savedMessages.get(0).getSenderId())
            .isEqualTo(senderId);

        assertThat(savedMessages.get(0).getContent())
            .isEqualTo(
                "전송 실패 메시지"
            );

        assertThat(savedMessages.get(0).getReadAt())
            .isNull();
    }

    private WebSocketStompClient createStompClient() {
        WebSocketStompClient client =
            new WebSocketStompClient(
                new StandardWebSocketClient()
            );

        MappingJackson2MessageConverter converter =
            new MappingJackson2MessageConverter();

        // Spring Boot가 설정한 ObjectMapper를 사용해
        // Instant와 record DTO를 변환한다.
        converter.setObjectMapper(objectMapper);

        client.setMessageConverter(converter);
        client.setTaskScheduler(taskScheduler);
        client.setDefaultHeartbeat(CLIENT_HEARTBEAT);

        return client;
    }

    private ThreadPoolTaskScheduler createTaskScheduler() {
        ThreadPoolTaskScheduler scheduler =
            new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(1);

        scheduler.setThreadNamePrefix(
            "dm-stomp-test-client-"
        );

        scheduler.initialize();

        return scheduler;
    }

    private String webSocketUrl() {
        // /ws는 SockJS endpoint이므로 실제 native WebSocket 주소는
        // /ws/websocket이 된다.
        return "ws://localhost:"
            + port
            + "/ws/websocket";
    }

    private User createUser(
        String name
    ) {
        return User.builder()
            .email(
                UUID.randomUUID()
                    + "@test.com"
            )
            .passwordHash("hash")
            .name(name)
            .role(UserRole.USER)
            .locked(false)
            .build();
    }

    private List<DirectMessage> waitForMessageCount(
        int expectedCount
    ) throws InterruptedException {
        long deadline =
            System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);

        List<DirectMessage> messages =
            directMessageRepository.findAll();

        while (
            messages.size() != expectedCount
                && System.nanoTime() < deadline
        ) {
            Thread.sleep(50);

            messages =
                directMessageRepository.findAll();
        }

        assertThat(messages)
            .as(
                "제한 시간 안에 저장된 DM 개수가 %d개가 되어야 한다.",
                expectedCount
            )
            .hasSize(expectedCount);

        return messages;
    }

    @Test
    @DisplayName("재접속 후 REST 복합 커서 조회로 놓친 DM을 복구한다")
    void reconnect_recoversMissedMessageByRestCursor()
        throws Exception {

        // given: 발신자와 수신자가 WebSocket에 연결한다.
        StompSession senderSession =
            connectAs(senderId);

        StompSession receiverSession =
            connectAs(receiverId);

        // 수신자가 해당 대화방의 실시간 DM을 구독한다.
        CompletableFuture<DirectMessageDto> received =
            subscribeDirectMessages(
                receiverSession
            );

        // 수신자가 연결된 상태에서 첫 번째 메시지를 전송한다.
        senderSession.send(
            "/pub/conversations/"
                + conversationId
                + "/direct-messages",
            new DirectMessageSendRequest(
                "연결 중 메시지"
            )
        );

        DirectMessageDto firstMessage =
            received.get(
                5,
                TimeUnit.SECONDS
            );

        waitForMessageCount(1);

        // 첫 번째 메시지의 위치를 마지막으로 확인한 커서로 사용한다.
        String lastCursor =
            firstMessage.createdAt().toString();

        UUID lastMessageId =
            firstMessage.id();

        // 수신자의 WebSocket 연결을 종료해 오프라인 상태를 만든다.
        receiverSession.disconnect();

        // Simple Broker가 연결 종료와 구독 해제를 처리할 시간을 준다.
        Thread.sleep(
            SUBSCRIBE_SETTLE_MILLIS
        );

        // when: 수신자가 연결되지 않은 동안 두 번째 DM을 전송한다.
        senderSession.send(
            "/pub/conversations/"
                + conversationId
                + "/direct-messages",
            new DirectMessageSendRequest(
                "오프라인 메시지"
            )
        );

        // 실시간 수신자가 없어도 메시지는 DB에 저장되어야 한다.
        waitForMessageCount(2);

        // 수신자가 WebSocket에 다시 연결한다.
        StompSession reconnectedSession =
            connectAs(receiverId);

        assertThat(reconnectedSession.isConnected())
            .isTrue();

        // 첫 번째 메시지 이후에 저장된 메시지만 조회하도록
        // createdAt과 id를 복합 커서로 전달한다.
        URI recoveryUri =
            UriComponentsBuilder
                .fromUriString(
                    "http://localhost:" + port
                )
                .path(
                    "/api/conversations/"
                        + conversationId
                        + "/direct-messages"
                )
                .queryParam(
                    "cursor",
                    lastCursor
                )
                .queryParam(
                    "idAfter",
                    lastMessageId
                )
                .queryParam(
                    "limit",
                    10
                )
                .queryParam(
                    "sortDirection",
                    "ASCENDING"
                )
                .queryParam(
                    "sortBy",
                    "createdAt"
                )
                .build()
                .encode()
                .toUri();

        HttpHeaders headers =
            new HttpHeaders();

        // WebSocket 재연결에 사용한 사용자와 같은 인증 토큰으로
        // REST 복구 API를 호출한다.
        headers.setBearerAuth(
            "valid-token-" + receiverId
        );

        HttpEntity<Void> request =
            new HttpEntity<>(headers);

        ResponseEntity<
            CursorResponse<DirectMessageDto>
            > response =
            restTemplate.exchange(
                recoveryUri,
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<
                    CursorResponse<DirectMessageDto>
                    >() {
                }
            );

        // then
        assertThat(response.getStatusCode())
            .isEqualTo(HttpStatus.OK);

        CursorResponse<DirectMessageDto> body =
            response.getBody();

        assertThat(body)
            .isNotNull();

        // 첫 번째 메시지는 커서 이전이므로 제외되고
        // 오프라인 중 저장된 두 번째 메시지만 반환되어야 한다.
        assertThat(body.data())
            .hasSize(1);

        DirectMessageDto recoveredMessage =
            body.data().get(0);

        assertThat(recoveredMessage.content())
            .isEqualTo(
                "오프라인 메시지"
            );

        assertThat(recoveredMessage.sender().userId())
            .isEqualTo(senderId);

        assertThat(recoveredMessage.receiver().userId())
            .isEqualTo(receiverId);

        // totalCount는 커서 이후 개수가 아니라
        // 해당 대화에 저장된 전체 메시지 개수다.
        assertThat(body.totalCount())
            .isEqualTo(2L);

        assertThat(body.hasNext())
            .isFalse();
    }
}
