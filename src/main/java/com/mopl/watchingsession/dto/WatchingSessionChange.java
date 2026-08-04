package com.mopl.watchingsession.dto;

public record WatchingSessionChange(
    ChangeType type,
    WatchingSessionDto watchingSessionDto,
    long watcherCount
) {
    public static WatchingSessionChange join(WatchingSessionDto watchingSession, long watcherCount) {
        return new WatchingSessionChange(ChangeType.JOIN, watchingSession, watcherCount);
    }

    public static WatchingSessionChange leave(WatchingSessionDto watchingSession, long watcherCount) {
        return new WatchingSessionChange(ChangeType.LEAVE, watchingSession, watcherCount);
    }

}
