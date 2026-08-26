package com.mopl.watchingsession.websocket.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.global.exception.ErrorCode;
import com.mopl.global.security.websocket.StompErrorFrameSender;
import com.mopl.watchingsession.presence.ContentExistenceCache;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

@ExtendWith(MockitoExtension.class)
class WatchingSessionSubscribeExistenceInterceptorTest {

    @Mock
    private ContentExistenceCache contentExistenceCache;

    @Mock
    private StompErrorFrameSender errorFrameSender;

    @InjectMocks
    private WatchingSessionSubscribeExistenceInterceptor interceptor;

    private Message<?> subscribeMessage(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("존재하는 콘텐츠의 watch 토픽 구독 통과")
    void preSend_passesThrough_whenContentExists() {
        // given
        UUID contentId = UUID.randomUUID();
        when(contentExistenceCache.exists(contentId)).thenReturn(true);
        Message<?> message = subscribeMessage("/sub/contents/" + contentId + "/watch");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠의 watch 토픽 구독은 null을 반환해 브로커 전달을 막고 에러프레임 직접 전송")
    void preSend_blocksMessageAndSendsErrorFrame_whenContentDoesNotExist() {
        // given
        UUID contentId = UUID.randomUUID();
        when(contentExistenceCache.exists(contentId)).thenReturn(false);
        Message<?> message = subscribeMessage("/sub/contents/" + contentId + "/watch");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then : 메시지 전달 중단
        assertThat(result).isNull();

        // 클라이언트에게는 직접 에러 프레임을 전송해야 함
        verify(errorFrameSender).send(
            eq(message),
            eq("BusinessException"),
            eq(ErrorCode.CONTENT_NOT_FOUND),
            eq(ErrorCode.CONTENT_NOT_FOUND.getMessage()),
            eq(Map.of())
        );}

    @Test
    @DisplayName("watch 토픽이 아닌 SUBSCRIBE는 관여하지 않고 그대로 통과")
    void preSend_ignoresNonWatchSubscribe() {
        // given
        UUID conversationId = UUID.randomUUID();
        Message<?> message = subscribeMessage("/sub/conversations/" + conversationId + "/direct-messages");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then: 이 인터셉터의 관심사가 아니므로 DB 조회 자체를 하지 않아야 함
        assertThat(result).isSameAs(message);
        verifyNoInteractions(contentExistenceCache);
    }

    @Test
    @DisplayName("SUBSCRIBE가 아닌 커맨드는 관여하지 않음")
    void preSend_ignoresNonSubscribeCommand() {
        // given
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/pub/contents/" + UUID.randomUUID() + "/chat");
        accessor.setLeaveMutable(true);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isSameAs(message);
        verifyNoInteractions(contentExistenceCache);
    }

    @Test
    @DisplayName("contentId가 올바른 UUID 형식이 아니면 CONTENT_NOT_FOUND로 차단")
    void preSend_throwsContentNotFound_whenContentIdIsInvalidUuid() {
        // given: 정규식 자체는 36자 형태를 요구하므로 통과하지만 UUID.fromString이 실패하는 값
        // 실제 STOMP 파이프라인에서는 StompDestinationAUthorizationInterceptor가 먼저 실행되어 먼저 걸러내므로
        // 이 인터셉터가 단독으로 쓰이거나 체인 순서가 바뀌는 경우에 대한 방어적 코드
        Message<?> message = subscribeMessage("/sub/contents/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaaa/watch");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then: 메시지 전달 중단
        assertThat(result).isNull();
        verifyNoInteractions(contentExistenceCache);

        // 클라이언트에게는 직접 에러 프레임을 전송해야 함
        verify(errorFrameSender).send(
            same(message),
            eq("BusinessException"),
            eq(ErrorCode.CONTENT_NOT_FOUND),
            eq(ErrorCode.CONTENT_NOT_FOUND.getMessage()),
            eq(Map.of())
        );
    }

    @Test
    @DisplayName("존재하는 콘텐츠의 chat 토픽 구독 통과")
    void preSend_passesThrough_whenChatContentExists() {
        // given
        UUID contentId = UUID.randomUUID();
        when(contentExistenceCache.exists(contentId)).thenReturn(true);
        Message<?> message = subscribeMessage("/sub/contents/" + contentId + "/chat");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isSameAs(message);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠의 chat 토픽 구독은 null을 반환해 브로커 전달을 막고 에러프레임 직접 전송")
    void preSend_blocksMessageAndSendsErrorFrame_whenChatContentDoesNotExist() {
        // given
        UUID contentId = UUID.randomUUID();
        when(contentExistenceCache.exists(contentId)).thenReturn(false);
        Message<?> message = subscribeMessage("/sub/contents/" + contentId + "/chat");

        // when
        Message<?> result = interceptor.preSend(message, null);

        // then
        assertThat(result).isNull();
        verify(errorFrameSender).send(
            eq(message),
            eq("BusinessException"),
            eq(ErrorCode.CONTENT_NOT_FOUND),
            eq(ErrorCode.CONTENT_NOT_FOUND.getMessage()),
            eq(Map.of())
        );
    }
}
