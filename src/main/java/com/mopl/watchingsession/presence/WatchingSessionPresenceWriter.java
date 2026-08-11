package com.mopl.watchingsession.presence;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 시청 presence를 Redis에 쓰고 지우는 컴포넌트입니다.
 *
 * 이번 PR에서는 이 값을 읽는 경로가 아직 없습니다. 조회·소유권 판정·시청자 수 집계는
 * 기존 DB 스냅샷/인메모리 경로를 그대로 사용하므로, Redis 쓰기 실패가 시청 시작·종료 자체를
 * 막아서는 안 됩니다. 그래서 예외를 호출자에게 전파하지 않고 로그만 남깁니다.
 * TODO(E-11): Redis가 소유권 판정의 SSOT가 되면 실패를 전파하도록 변경.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionPresenceWriter {

    private static final String KEY_TEMPLATE = "mopl:presence:watcher:%s";

    private final RedisTemplate<String, Object> redisTemplate;

    public void write(UUID watcherId, UUID contentId, String sessionId, String subscriptionId,
        Instant startedAt, Duration ttl) {
        WatchingPresence presence = new WatchingPresence(watcherId, contentId, sessionId,
            subscriptionId, startedAt);
        try {
            // set(key, value, ttl) 단일 호출 - SET과 EXPIRE를 분리하면 중간 실패 시 만료 없는 키가 남음
            redisTemplate.opsForValue().set(key(watcherId), presence, ttl);
        } catch (RuntimeException e) {
            log.error("Presence 쓰기 실패: watcherId={}, contentId={}", watcherId, contentId, e);
        }
    }

    public void delete(UUID watcherId) {
        try {
            redisTemplate.delete(key(watcherId));
        } catch (RuntimeException e) {
            log.error("Presence 삭제 실패: watcherId={}", watcherId, e);
        }
    }

    private String key(UUID watcherId) {
        return KEY_TEMPLATE.formatted(watcherId);
    }
}
