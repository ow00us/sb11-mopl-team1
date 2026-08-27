package com.mopl.sse.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class SseRecentEventIds {

    private final Set<UUID> eventIds;

    SseRecentEventIds(
        int maxSize
    ) {
        if (maxSize < 1) {
            throw new IllegalArgumentException(
                "maxSize는 1 이상이어야 합니다."
            );
        }

        Map<UUID, Boolean> bounded =
            new LinkedHashMap<>(
                16,
                0.75f,
                false
            ) {
                @Override
                protected boolean removeEldestEntry(
                    Map.Entry<UUID, Boolean> eldest
                ) {
                    return size() > maxSize;
                }
            };

        eventIds =
            Collections.synchronizedSet(
                Collections.newSetFromMap(
                    bounded
                )
            );
    }

    boolean markSeen(
        UUID eventId
    ) {
        return eventIds.add(eventId);
    }
}
