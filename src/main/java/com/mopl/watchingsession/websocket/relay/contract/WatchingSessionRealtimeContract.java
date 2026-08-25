package com.mopl.watchingsession.websocket.relay.contract;

import java.util.UUID;

public final class WatchingSessionRealtimeContract {

    public static final String EVENT_TYPE = "watching-session.changed";

    private static final String DESTINATION = "/sub/contents/%s/watch";

    private WatchingSessionRealtimeContract() {}

    public static String getDestination(UUID contentId) {
        return DESTINATION.formatted(contentId);
    }

}
