package com.mopl.directmessage.websocket;

import com.mopl.directmessage.repository.ConversationParticipantRepository;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class DirectMessageAuthorizationInterceptor implements ChannelInterceptor {

    private static final Pattern SEND_DESTINATION_PATTERN =
        Pattern.compile(
            "^/pub/conversations/([^/]+)/direct-messages$"
        );

    private static final Pattern SUBSCRIBE_DESTINATION_PATTERN =
        Pattern.compile(
            "^/sub/conversations/([^/]+)/direct-messages$"
        );

    private final ConversationParticipantRepository participantRepository;

    @Override
    public Message<?> preSend(
        Message<?> message,
        MessageChannel channel
    ) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(
                message,
                StompHeaderAccessor.class
            );

        if (accessor == null) {
            return message;
        }

        Pattern destinationPattern =
            getDestinationPattern(accessor.getCommand());

        if (destinationPattern == null) {
            return message;
        }

        String destination = accessor.getDestination();

        if (destination == null) {
            return message;
        }

        Matcher matcher = destinationPattern.matcher(destination);

        //DM 경로가 아니면 다른 WebSocket 기능이 처리하게 둔다.
        if (!matcher.matches()) {
            return message;
        }

        UUID conversationId = parseConversationId(matcher.group(1));

        UUID requesterId = getRequesterId(accessor.getUser());

        boolean isParticipant =
            participantRepository.existsByConversationIdAndUserId(
                conversationId,
                requesterId
            );

        if (!isParticipant) {
            throw new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        return message;
    }

    private Pattern getDestinationPattern(
        StompCommand command
    ) {
        if (StompCommand.SEND.equals(command)) {
            return SEND_DESTINATION_PATTERN;
        }

        if (StompCommand.SUBSCRIBE.equals(command)) {
            return SUBSCRIBE_DESTINATION_PATTERN;
        }

        return null;
    }

    private UUID parseConversationId(
        String conversationId
    ) {
        try {
            return UUID.fromString(conversationId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "대화 ID 형식이 올바르지 않습니다."
            );
        }
    }

    private UUID getRequesterId(
        Principal principal
    ) {
        if (principal == null) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }

        try {
            return UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                ErrorCode.UNAUTHORIZED
            );
        }
    }

}
