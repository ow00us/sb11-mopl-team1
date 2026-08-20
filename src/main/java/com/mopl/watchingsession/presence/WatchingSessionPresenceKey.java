package com.mopl.watchingsession.presence;

import java.util.UUID;

/**
 * presence Redis 키 규약. Writer(쓰기)와 Reader(읽기)가 같은 키를 계산해야 하므로 공유한다.
 */
public class WatchingSessionPresenceKey {

    private static final String KEY_TEMPLATE = "mopl:presence:watcher:%s";

    static String of(UUID watcherId) {
        return KEY_TEMPLATE.formatted(watcherId);
    }

    private WatchingSessionPresenceKey() {
    }

}
