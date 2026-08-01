package com.mopl.global.security.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.JwtProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

public class StompAuthChannelInterceptorTest {

    private final JwtProvider jwtProvider = mock(JwtProvider.class);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(jwtProvider);

    @Test
    @DisplayName("유효한 Bearer 토큰으로 CONNECT하면 Principal이 바인딩됨")
    void connect_withValidToken_bindsPrincipal() {
        String token = "valid-token";
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            "user-id", null, List.of());
        when(jwtProvider.validate(token)).thenReturn(true);
        when(jwtProvider.getAuthentication(token)).thenReturn(authentication);

        Message<?> connectMessage = connectMessageWithAuthorizationHeader("Bearer " + token);

        interceptor.preSend(connectMessage, null);

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(connectMessage);
        assertThat(accessor.getUser()).isEqualTo(authentication);
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 UNAUTHORIZED 예외 발생")
    void connect_withoutAuthorizationHeader_throwsUnauthorized() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<?> connectMessage = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(connectMessage, null))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("CONNECT가 아닌 프레임은 인증 검사를 하지 않음")
    void nonConnectFrame_skipsAuthentication() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        Message<?> sendMessage = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(sendMessage, null);

        assertThat(result).isEqualTo(sendMessage);
    }

    @Test
    @DisplayName("유효하지 않은 토큰이면 UNAUTHORIZED 예외 발생")
    void connect_withInvalidToken_throwsUnauthorized() {
        String token = "invalid-token";
        when(jwtProvider.validate(token)).thenReturn(false);
        Message<?> connectMessage = connectMessageWithAuthorizationHeader("Bearer " + token);

        assertThatThrownBy(() -> interceptor.preSend(connectMessage, null))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Bearer prefix가 없으면 UNAUTHORIZED 예외 발생")
    void connect_withoutBearerPrefix_throwsUnauthorized() {
        Message<?> connectMessage = connectMessageWithAuthorizationHeader("token-only");

        assertThatThrownBy(() -> interceptor.preSend(connectMessage, null))
            .isInstanceOf(BusinessException.class);
    }

    private Message<?> connectMessageWithAuthorizationHeader(String value) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", value);
        accessor.setLeaveMutable(true); // 실제 STOMP 처리 흐름과 동일하게 mutable 유지
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

}
