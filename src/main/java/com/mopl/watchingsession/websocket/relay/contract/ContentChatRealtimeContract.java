package com.mopl.watchingsession.websocket.relay.contract;

import java.util.UUID;

public final class ContentChatRealtimeContract {

    public static final String EVENT_TYPE = "content-chat.sent";

    private static final String DESTINATION = "/sub/contents/%s/chat";

    private ContentChatRealtimeContract() {}

    public static String getDestination(UUID contentId) {
        return DESTINATION.formatted(contentId);
    }

}
