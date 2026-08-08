package com.mopl.watchingsession.websocket.stompsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.global.common.UserSummary;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
public class ChatSenderCachingInterceptorTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ChatSenderCacheInitializer interceptor;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // STOMP 프레임과 동일한 환경의 Message 객체를 생성하는 헬퍼 메서드
    private Message<?> createMessage(StompCommand command, Principal principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (principal != null) {
            accessor.setUser(principal);
        }
        // 캐시를 저장할 수 있도록 빈 SessionAttributes 맵 초기화
        accessor.setSessionAttributes(new HashMap<>());

        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Principal principalOf(UUID userId) {
        return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null, List.of());
    }

    @Test
    @DisplayName("CONNECT 명령어고 유효한 Principal이면 DB에서 유저를 조회해 세션에 캐싱함")
    void preSend_connectWithValidPrincipal_cachesUserSummary() {
        // given
        Message<?> message = createMessage(StompCommand.CONNECT, principalOf(USER_ID));

        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(USER_ID);
        when(mockUser.getName()).thenReturn("우디");
        when(mockUser.getProfileImageUrl()).thenReturn("url");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(mockUser));

        // when
        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        // then
        assertThat(result).isNotNull();

        StompHeaderAccessor resultAccessor = StompHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor).isNotNull();

        UserSummary cachedSender = ChatSenderCache.get(resultAccessor);

        assertThat(cachedSender).isNotNull();
        assertThat(cachedSender.name()).isEqualTo("우디");
    }

    @Test
    @DisplayName("CONNECT 명령어지만 DB에 유저가 없으면 예외를 던지지 않고 통과(캐시 미스)")
    void preSend_connectButUserNotFound_passesWithoutException() {
        // given
        Message<?> message = createMessage(StompCommand.CONNECT, principalOf(USER_ID));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        // when
        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        // then
        assertThat(result).isNotNull();

        StompHeaderAccessor resultAccessor = StompHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(resultAccessor).isNotNull();
        UserSummary cachedSender = ChatSenderCache.get(resultAccessor);

        assertThat(cachedSender).isNull();
    }

    @Test
    @DisplayName("CONNECT 명령어가 아니면 DB 조회를 건너뛰고 바로 통과")
    void preSend_notConnectCommand_skipsCaching() {
        // given
        Message<?> message = createMessage(StompCommand.SEND, principalOf(USER_ID));

        // when
        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        // then
        assertThat(result).isNotNull();
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("CONNECT 명령어지만 Principal이 없으면 DB 조회 건너뛰고 통과")
    void preSend_connectWithoutPrincipal_skipsCaching() {
        // given
        Message<?> message = createMessage(StompCommand.CONNECT, null);

        // when
        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        // then
        assertThat(result).isNotNull();
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("CONNECT 명령어지만 Principal 이름이 UUID 형식이 아니면 캐싱을 건너뜀")
    void preSend_connectWithInvalidUuidPrincipal_skipsCaching() {
        // given
        Principal invalidPrincipal = UsernamePasswordAuthenticationToken
            .authenticated("invalid-uuid-string", null, java.util.List.of());
        Message<?> message = createMessage(StompCommand.CONNECT, invalidPrincipal);

        // when
        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        // then
        assertThat(result).isNotNull();
        verify(userRepository, never()).findById(any());

        StompHeaderAccessor resultAccessor = StompHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(java.util.Objects.requireNonNull(resultAccessor)).isNotNull();
        assertThat(ChatSenderCache.get(resultAccessor)).isNull();
    }

    @Test
    @DisplayName("STOMP 명령어 프레임으로 연결을 시도해도 유저를 조회해 세션에 캐싱")
    void preSend_stompCommandWithValidPrincipal_cachesUserSummary() {
        // given
        Message<?> message = createMessage(StompCommand.STOMP, principalOf(USER_ID)); // CONNECT 대신 STOMP 사용

        User mockUser = mock(User.class);
        when(mockUser.getId()).thenReturn(USER_ID);
        when(mockUser.getName()).thenReturn("우디");

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(mockUser));

        // when
        Message<?> result = interceptor.preSend(message, mock(MessageChannel.class));

        // then
        assertThat(result).isNotNull();
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        UserSummary cachedSender = ChatSenderCache.get(java.util.Objects.requireNonNull(resultAccessor));

        assertThat(cachedSender).isNotNull();
        assertThat(cachedSender.name()).isEqualTo("우디");
    }

}
