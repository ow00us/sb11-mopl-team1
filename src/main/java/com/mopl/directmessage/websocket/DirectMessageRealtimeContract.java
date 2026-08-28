package com.mopl.directmessage.websocket;

import java.util.UUID;

final class DirectMessageRealtimeContract {

    static final String CREATED_EVENT_TYPE =
        "direct-message.created";

    static final String READ_EVENT_TYPE =
        "direct-message.read";

    private static final String DESTINATION =
        "/sub/conversations/%s/direct-messages";

    private DirectMessageRealtimeContract() {
    }

    static String destination(
        UUID conversationId
    ) {
        return DESTINATION.formatted(
            conversationId
        );
    }
}
