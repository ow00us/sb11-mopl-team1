package com.mopl.watchingsession.websocket.relay.payload;

import com.mopl.watchingsession.dto.ContentChatDto;
import java.util.UUID;

public record ContentChatRelayPayload(
    UUID contentId,
    ContentChatDto chat
) {
}
