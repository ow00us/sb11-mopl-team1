package com.mopl.directmessage.websocket;

import com.mopl.directmessage.dto.DirectMessageDto;
import com.mopl.directmessage.dto.DirectMessageSendRequest;
import com.mopl.directmessage.service.DirectMessageService;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class DirectMessageWebSocketController {

    private final DirectMessageService directMessageService;
    private final DirectMessageBroadcaster broadcaster;

    @MessageMapping("/conversations/{conversationId}/direct-messages")
    public void send(
        @DestinationVariable UUID conversationId,
        @Payload DirectMessageSendRequest request,
        Principal principal
    ) {
        UUID senderId = getSenderId(principal);

        DirectMessageDto savedMessage =
            directMessageService.create(
                senderId,
                conversationId,
                request.content()
            );

        broadcaster.broadcast(
            conversationId,
            savedMessage
        );
    }

    private UUID getSenderId(Principal principal) {
        if (principal == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }
    }
}
