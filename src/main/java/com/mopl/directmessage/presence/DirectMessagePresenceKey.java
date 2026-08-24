package com.mopl.directmessage.presence;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

final class DirectMessagePresenceKey {

    private static final String ROOT =
        "mopl:presence:dm";

    private DirectMessagePresenceKey() {
    }

    static String conversation(
        UUID userId,
        UUID conversationId
    ) {
        return conversationPrefix(userId)
            + conversationId;
    }

    static String conversationPrefix(
        UUID userId
    ) {
        return ROOT
            + ":{"
            + userId
            + "}:conversation:";
    }

    static String session(
        UUID userId,
        String instanceId,
        String sessionId
    ) {
        return ROOT
            + ":{"
            + userId
            + "}:instance:"
            + component(instanceId)
            + ":session:"
            + component(sessionId);
    }

    static String component(
        String value
    ) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                value.getBytes(
                    StandardCharsets.UTF_8
                )
            );
    }
}
