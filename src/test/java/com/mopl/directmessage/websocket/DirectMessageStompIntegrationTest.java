package com.mopl.directmessage.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.mopl.directmessage.entity.Conversation;
import com.mopl.directmessage.entity.ConversationParticipant;
import com.mopl.directmessage.entity.ParticipantSlot;
import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.directmessage.repository.ConversationRepository;
import com.mopl.directmessage.repository.DirectMessageRepository;
import com.mopl.global.security.JwtProvider;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
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

    @LocalServerPort
    private int port;

    // 실제 JWT 문자열을 만들지 않고 인증 결과만 테스트한다.
    @MockitoBean
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationParticipantRepository
        participantRepository;

    @Autowired
    private DirectMessageRepository directMessageRepository;

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
                    }
                )
                .get(
                    5,
                    TimeUnit.SECONDS
                );

        sessions.add(session);

        return session;
    }

    private WebSocketStompClient createStompClient() {
        WebSocketStompClient client =
            new WebSocketStompClient(
                new StandardWebSocketClient()
            );

        // JSON 요청과 DirectMessageDto 응답을 Java 객체로 변환한다.
        client.setMessageConverter(
            new MappingJackson2MessageConverter()
        );

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
}
