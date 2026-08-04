package com.mopl.watchingsession.websocket;

import com.mopl.global.common.UserSummary;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP CONNECT 시점에 인증된 사용자(Principal)의 UserSummary를 1회 조회해
 * WebSocket 세션 attribute에 캐싱한다.
 *
 * StompAuthChannelInterceptor가 이미 CONNECT 프레임에서 Principal 바인딩을 마친 뒤에
 * 실행되어야 하므로, WebSocketConfig에 이 인터셉터를 인증 인터셉터 다음 순서로 등록한다.
 *
 * 목적: 콘텐츠 실시간 채팅(ContentChatController)에서 SEND마다 User를 재조회하지 않고
 * 이 캐시를 사용해 RDB 부하를 줄이기 위함.
 *
 * User 조회에 실패해도(극단적으로 탈퇴 등) 여기서 연결 자체를 거부하지 않는다.
 * 인증은 이미 통과했으므로 CONNECT 자체는 성공시키고, 캐싱만 건너뛴다.
 * 이 경우 채팅 전송 시점에 ContentChatService가 캐시 미스를 감지해 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSenderCachingInterceptor implements ChannelInterceptor {

    private final UserRepository userRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        UUID userId = extractUserId(accessor.getUser());
        if (userId == null) {
            log.warn("CONNECT 시점에 인증 정보가 없어 채팅 발신자 캐싱을 건너뜁니다.");
            return message;
        }

        userRepository.findById(userId).ifPresentOrElse(
            user -> ChatSenderCache.put(accessor, toUserSummary(user)),
            () -> log.warn("CONNECT 시점 사용자 조회 실패, 채팅 발신자 캐싱을 건너뜁니다: userId={}", userId)
        );

        return message;
    }

    private UserSummary toUserSummary(User user) {
        return new UserSummary(user.getId(), user.getName(), user.getProfileImageUrl());
    }

    private UUID extractUserId(Principal principal) {
        if (principal == null) {
            return null;
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

 }
