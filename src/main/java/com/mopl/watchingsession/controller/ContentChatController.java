package com.mopl.watchingsession.controller;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.watchingsession.dto.ContentChatSendRequest;
import com.mopl.watchingsession.service.ContentChatService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ContentChatController {

    private final ContentChatService contentChatService;

    @MessageMapping("/contents/{contentId}/chat")
    public void sendChat(
        @DestinationVariable UUID contentId,
        @Payload @Valid ContentChatSendRequest request,
        Principal principal
    ) {
        UUID senderId = extractSenderId(principal);
        contentChatService.sendAndBroadcast(senderId, contentId, request.content());
    }

    private UUID extractSenderId(Principal principal) {
        // CONNECT 시점에 StompAuthChannelInterceptor가 이미 인증을 강제하므로
        // 이 시점의 principal은 null이 아니어야 정상이나 방어적으로 명시적 예외 처리
        if (principal == null || principal.getName() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "인증되지 않은 연결에서 채팅을 전송할 수 없습니다.");
        }
        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 인증 정보입니다.");
        }
    }
}
