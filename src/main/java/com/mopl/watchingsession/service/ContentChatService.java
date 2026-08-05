package com.mopl.watchingsession.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import com.mopl.watchingsession.entity.WatchingSessionSnapshot;
import com.mopl.watchingsession.repository.WatchingSessionSnapshotRepository;
import java.time.Instant;
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
    private final WatchingSessionSnapshotRepository watchingSessionSnapshotRepository;

    public void sendAndBroadcast(UUID senderId, UUID contentId, @Nullable UserSummary sender, String content) {
        // 시청 검증 수행 -> 정상 시청 중이면 콘텐츠 존재 여부 쿼리 생략
        // TODO: [성능 최적화] 추후 Redis 전환 시 Redis Presence 확인 로직으로 대체
        validateWatchingAndContent(senderId, contentId);

        // 캐시 미스 극단 케이스 대응
        UserSummary actualSender = (sender != null) ? sender : getSenderFallback(senderId);

        // 브로드캐스트
        ContentChatDto chatDto = new ContentChatDto(actualSender, content);
        messagingTemplate.convertAndSend(DESTINATION_TEMPLATE.formatted(contentId), chatDto);
    }

    private UserSummary getSenderFallback(UUID senderId) {
        User user = userRepository.findById(senderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 계정입니다."));
        return new UserSummary(user.getId(), user.getName(), user.getProfileImageUrl());
    }

    private void validateWatchingAndContent(UUID senderId, UUID contentId) {
        // 콘텐츠는 @SQLDelete로 논리 삭제되어도 시청 세션 FK 행은 그대로 남을 수 있으므로,
        // 스냅샷 유효성과 무관하게 콘텐츠 존재 여부를 항상 먼저 확인한다.
        // (스냅샷만 보고 존재 쿼리를 생략하면, 콘텐츠 삭제 후에도 세션 만료 전까지 채팅이 가능해지는 구멍이 생김)
        if (!contentRepository.existsById(contentId)) {
            throw new BusinessException(ErrorCode.CONTENT_NOT_FOUND);
        }

        WatchingSessionSnapshot snapshot = watchingSessionSnapshotRepository.findByWatcherId(senderId).orElse(null);

        boolean isWatchingThisContent = snapshot != null
            && contentId.equals(snapshot.getContentId())
            && !snapshot.isExpired(Instant.now());

        if (!isWatchingThisContent) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "시청 중인 콘텐츠에서만 채팅을 보낼 수 있습니다.");
        }
    }
}
