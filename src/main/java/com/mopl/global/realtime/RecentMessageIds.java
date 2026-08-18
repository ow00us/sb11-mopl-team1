package com.mopl.global.realtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 최근에 처리한 메시지 식별자를 기억합니다.
 *
 * <p>Redis Pub/Sub 은 재연결이나 재발행으로 같은 메시지가 두 번 도착할 수 있습니다. 그대로
 * 전달하면 사용자에게 알림이 두 번 갑니다.
 *
 * <p>크기 상한을 둡니다. 무한히 모으면 오래 뜬 인스턴스에서 메모리가 계속 늘어납니다. 상한을
 * 넘으면 가장 오래된 것부터 잊습니다. 그만큼 지난 메시지가 다시 오면 걸러내지 못하지만, 그
 * 간격을 넘겨 도착하는 중복은 실시간 전달에서 의미가 없습니다.
 */
class RecentMessageIds {

    private final Set<UUID> ids;

    RecentMessageIds(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize 는 1 이상이어야 합니다. 실제 " + maxSize);
        }

        Map<UUID, Boolean> bounded = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > maxSize;
            }
        };
        // 구독 컨테이너가 여러 스레드로 메시지를 넘길 수 있습니다.
        this.ids = Collections.synchronizedSet(Collections.newSetFromMap(bounded));
    }

    /**
     * 처음 보는 식별자면 기억하고 {@code true} 를 돌려줍니다.
     *
     * @return 처음 본 메시지면 {@code true}, 이미 처리한 메시지면 {@code false}
     */
    boolean markSeen(UUID messageId) {
        return ids.add(messageId);
    }
}
