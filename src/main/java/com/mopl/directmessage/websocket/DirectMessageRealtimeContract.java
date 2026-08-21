package com.mopl.directmessage.websocket;

import java.util.UUID;

final class DirectMessageRealtimeContract {

    static final String EVENT_TYPE =
        "direct-message.created";

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
