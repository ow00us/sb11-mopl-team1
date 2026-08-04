package com.mopl.watchingsession.service;

import com.mopl.content.repository.ContentRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.user.entity.User;
import com.mopl.user.repository.UserRepository;
import com.mopl.watchingsession.dto.ContentChatDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(readOnly = true)
    public void sendAndBroadcast(UUID senderId, UUID contentId, String content) {
        // 콘텐츠 존재 여부 검증
        // TODO: [성능 최적화] 트래픽 증가 시 RDB 부하 방지를 위해 Redis 캐싱 고려
        if (!contentRepository.existsById(contentId)) {
            throw new BusinessException(ErrorCode.CONTENT_NOT_FOUND);
        }

        // 발신자 정보 조회
        // TODO: [성능 최적화] 채팅 발송마다 User를 조회하므로 부하 위험.
        //  STOMP CONNECT시 세션에 UserInfo를 캐싱하는 방식 고려
        User sender = userRepository.findById(senderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 계정입니다."));

        // 브로드캐스트
        ContentChatDto chatDto = ContentChatDto.from(sender, content);
        messagingTemplate.convertAndSend(DESTINATION_TEMPLATE.formatted(contentId), chatDto);
    }
}
