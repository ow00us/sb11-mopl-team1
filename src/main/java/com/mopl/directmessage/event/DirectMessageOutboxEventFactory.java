package com.mopl.directmessage.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.directmessage.entity.DirectMessage;
import com.mopl.global.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DirectMessageOutboxEventFactory {

    static final String EVENT_TYPE = "direct-message.created";
    static final int EVENT_VERSION = 1;
    static final int MAX_CONTENT_PREVIEW_LENGTH = 100;

    private final ObjectMapper objectMapper;

    public EventEnvelope create(
        DirectMessage directMessage,
        UUID receiverId
    ) {
        DirectMessageCreatedPayload payload =
            new DirectMessageCreatedPayload(
                directMessage.getId(),
                directMessage.getConversationId(),
                directMessage.getSenderId(),
                receiverId,
                normalizeContentPreview(
                    directMessage.getContent()
                )
            );

        return new EventEnvelope(
            UUID.randomUUID(),
            EVENT_TYPE,
            EVENT_VERSION,
            directMessage.getCreatedAt(),
            directMessage.getId(),
            objectMapper.valueToTree(payload)
        );
    }

    private String normalizeContentPreview(
        String content
    ) {
        String normalized =
            content.strip().replaceAll("\\s+", " ");

        int codePointCount = normalized.codePointCount(0, normalized.length());

        if (codePointCount <= MAX_CONTENT_PREVIEW_LENGTH) {
            return normalized;
        }

        int endIndex = normalized.offsetByCodePoints(
            0,
            MAX_CONTENT_PREVIEW_LENGTH
        );

        return normalized.substring(0, endIndex);
    }
}
