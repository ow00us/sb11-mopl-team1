package com.mopl.watchingsession.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.presence.ContentChatBuffer;
import com.mopl.watchingsession.presence.WatchingSessionPresenceReader;
import com.mopl.watchingsession.websocket.relay.contract.ContentChatRealtimeContract;
import com.mopl.watchingsession.websocket.relay.publisher.ContentChatRelayPublisher;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 콘텐츠 실시간 채팅 처리 서비스
 * DB에 영속화하지 않고, 검증 후 즉시 구독자에게 브로드캐스트만 수행한다.
 */
@Service
@RequiredArgsConstructor
public class ContentChatService {

    private static final String DESTINATION_TEMPLATE = "/sub/contents/%s/chat";

    private final ContentRepository contentRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final WatchingSessionPresenceReader watchingSessionPresenceReader;
    private final ContentChatBuffer contentChatBuffer;
    private final ContentChatRelayPublisher contentChatRelayPublisher;

    public void sendAndBroadcast(UUID senderId, UUID contentId, @Nullable UserSummary sender, String content) {
        // presence 우선 확인 -> 시청 중이 아니면 DB에 닿지 않고 즉시 차단
        validateWatchingAndContent(senderId, contentId);

        // 캐시 미스 극단 케이스 대응
        UserSummary actualSender = (sender != null) ? sender : getSenderFallback(senderId);

        // 브로드캐스트, 실패 시 예외 그대로 전파 (STOMP ERROR 프레임으로 발신자에게 전달되므로 발신자가 실패를 인지할 수 있음)
        ContentChatDto chatDto = new ContentChatDto(actualSender, content);
        broadcast(contentId, chatDto);

        // 브로드캐스트가 끝난 뒤에만 기록
        contentChatBuffer.append(contentId, chatDto);

        // 중계 발행 실패는 boolean으로만 알려지고 예외를 던지지 않으므로 SEND 자체는 계속 성공
        contentChatRelayPublisher.publish(contentId, chatDto);
    }

    public void broadcast(UUID contentId, ContentChatDto chatDto) {
        messagingTemplate.convertAndSend(ContentChatRealtimeContract.getDestination(contentId), chatDto);
    }

    private UserSummary getSenderFallback(UUID senderId) {
        User user = userRepository.findById(senderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 계정입니다."));
        return new UserSummary(user.getId(), user.getName(), user.getProfileImageUrl());
    }

    private void validateWatchingAndContent(UUID senderId, UUID contentId) {
        // presence 실패(Redis 예외)는 여기서 삼키지 않고 그대로 전파
        if (!watchingSessionPresenceReader.isWatching(senderId, contentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "시청 중인 콘텐츠에서만 채팅을 보낼 수 있습니다.");
        }

        // 콘텐츠는 @SQLDelete로 논리 삭제되어도 presence는 그 사실을 모르므로,
        // 시청 중이 확인된 뒤에도 콘텐츠 존재 여부는 별도로 확인한다.
        if (!contentRepository.existsById(contentId)) {
            throw new BusinessException(ErrorCode.CONTENT_NOT_FOUND, "존재하지 않는 콘텐츠입니다.");
        }
    }
}
