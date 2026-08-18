package com.mopl.watchingsession.presence;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 시청 presence를 Redis에 기록하고, 소유권 판정을 원자적으로 수행하는 컴포넌트입니다.
 *
 * presence 키는 "지금 이 사용자의 시청 세션을 어느 WebSocket 연결·구독이 소유하는가"의 원본입니다.
 * 소유권 비교와 삭제·연장 사이에 다른 요청이 끼어들면 낡은 구독이 현재 세션을 지우는 문제가 재발하므로,
 * 비교와 실행을 Lua 스크립트 하나로 묶어 원자적으로 처리합니다.
 * Redis는 스크립트 실행 도중 다른 클라이언트의 명령을 끼워넣지 않습니다.
 *
 * 값을 Hash로 저장하는 이유는 Lua가 HGET으로 필드를 직접 읽게 하기 위함입니다.
 * JSON 문자열로 두면 스크립트가 cjson 파싱과 필드명에 의존하게 되어, 레코드 필드명을 바꾸는
 * 순간 소유권 판정이 조용히 실패합니다.
 *
 * 인자·필드가 모두 평문 문자열이라 StringRedisTemplate을 사용합니다.
 * JSON 값 직렬화기를 쓰는 RedisTemplate<String, Object>로 ARGV를 넘기면 따옴표가 붙어
 * 문자열 비교가 항상 실패합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionPresenceWriter {

    private static final String KEY_TEMPLATE = "mopl:presence:watcher:%s";
    private static final String FIELD_SNAPSHOT_ID = "snapshotId";
    private static final String FIELD_CONTENT_ID = "contentId";
    private static final String FIELD_SESSION_ID = "sessionId";
    private static final String FIELD_SUBSCRIPTION_ID = "subscriptionId";
    private static final String FIELD_STARTED_AT = "startedAt";

    // 직전 소유자를 먼저 읽어둔 뒤 새 소유자로 덮어쓴다
    // 읽기와 쓰기가 한 스크립트 안에 있어야 연속 재구독 시나리오에서 각 호출이 자기가 밀ㄹ어낸 직전 소유자를 정확히 돌려받는다
    // DEL을 먼저 하는 이유는 스키마가 바뀌었을 때 옛 필드가 남지 않게 하기 위함
    private static final String SWAP_LUA = """
        local previous = redis.call('HGETALL', KEYS[1])
        redis.call('DEL', KEYS[1])
        redis.call('HSET', KEYS[1],
            'snapshotId', ARGV[1],
            'contentId', ARGV[2],
            'sessionId', ARGV[3],
            'subscriptionId', ARGV[4],
            'startedAt', ARGV[5])
        redis.call('PEXPIRE', KEYS[1], ARGV[6])
        return previous
        """;

    // 키가 없으면 HGET이 false를 반환해 문자열 비교가 실패
    // 1 = 삭제됨, 0 = 소유권 불일치(이상 신호), - 1= 활성 세션 없음(정상)
    private static final String DELETE_IF_OWNER_LUA = """
        if redis.call('EXISTS', KEYS[1]) == 0 then
          return -1
        end
        if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1]
            and redis.call('HGET', KEYS[1], 'subscriptionId') == ARGV[2] then
          return redis.call('DEL', KEYS[1])
        end
        return 0
        """;

    // PEXPIRE는 키가 있을 때만 1을 반환하고 키를 새로 만들지 않아 이미 만료된 presence를 heartbeat가 되살리지 않는다
    // 1 = 연장됨, 0 = 소유권 불일치(이상 신호), -1 = 활성 세션 없음(정상)
    private static final String RENEW_IF_OWNER_LUA = """
        if redis.call('EXISTS', KEYS[1]) == 0 then
          return -1
        end
        if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1]
            and redis.call('HGET', KEYS[1], 'subscriptionId') == ARGV[2] then
          return redis.call('PEXPIRE', KEYS[1], ARGV[3])
        end
        return 0
        """;

    // DefaultRedisScript는 본문의 SHA1을 캐싱해 EVALSHA로 실행되므로 인스턴스를 재사용한다
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SWAP_SCRIPT =
        new DefaultRedisScript<>(SWAP_LUA, List.class);
    private static final RedisScript<Long> DELETE_IF_OWNER_SCRIPT =
        new DefaultRedisScript<>(DELETE_IF_OWNER_LUA, Long.class);
    private static final RedisScript<Long> RENEW_IF_OWNER_SCRIPT =
        new DefaultRedisScript<>(RENEW_IF_OWNER_LUA, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 새 소유자를 기록하고, 이 호출이 밀어낸 직전 소유자를 반환한다.
     *
     * 실패를 격리하지 않고 그대로 전파한다. presence가 소유권의 원본이므로, 기록 실패를 삼키면
     * 소유자가 없는 상태로 시청이 시작되어 이후 퇴장 처리와 LEAVE 브로드캐스트가 통째로 유실된다.
     * 호출자(start)가 DB 스냅샷을 보상 삭제하고 클라이언트에 실패를 알려야 한다.
     */
    @SuppressWarnings("unchecked")
    public Optional<WatchingPresence> swap(UUID watcherId, UUID snapshotId, UUID contentId, String sessionId,
        String subscriptionId, Instant startedAt, Duration ttl) {

        List<String> previous = stringRedisTemplate.execute(
            SWAP_SCRIPT,
            List.of(key(watcherId)),
            snapshotId.toString(),
            contentId.toString(),
            nullSafe(sessionId),
            nullSafe(subscriptionId),
            startedAt.toString(),
            String.valueOf(ttl.toMillis()));

        return toPresence(watcherId, previous);
    }

    /**
     * 요청자가 현재 소유자일 때만 presence를 삭제한다.
     *
     * @return 실제로 삭제했으면 true. 소유권 불일치·키 없음·Redis 실패는 모두 false.
     */
    public boolean deleteIfOwner(UUID watcherId, String sessionId, String subscriptionId) {
        try {
            Long result = stringRedisTemplate.execute(
                DELETE_IF_OWNER_SCRIPT,
                List.of(key(watcherId)),
                nullSafe(sessionId),
                nullSafe(subscriptionId));

            if (result == 0L) {
                // 활성 세션은 있는데 소유자가 다름 -> 낡은 탭이 현재 소유자를 가리킴
                log.warn("Presence 소유권 불일치로 삭제 거부: watcherId={}", watcherId);
            }
            return result == 1L;
        } catch (RuntimeException e) {
            log.error("Presence 소유권 삭제 실패: watcherId={}", watcherId, e);
            return false;
        }
    }

    /**
     * 요청자가 현재 소유자일 때만 presence TTL을 재설정한다.
     *
     * @return 실제로 연장했으면 true. 소유권 불일치·키 없음·Redis 실패는 모두 false.
     */
    public boolean renewIfOwner(UUID watcherId, String sessionId, String subscriptionId, Duration ttl) {
        try {
            Long result = stringRedisTemplate.execute(
                RENEW_IF_OWNER_SCRIPT,
                List.of(key(watcherId)),
                nullSafe(sessionId),
                nullSafe(subscriptionId),
                String.valueOf(ttl.toMillis()));

            if (result == 0L) {
                log.warn("Presence 소유권 불일치로 TTL 연장 거부: watcherId={}", watcherId);
            }
            return result == 1L;
        } catch (RuntimeException e) {
            log.error("Presence TTL 갱신 실패: watcherId={}", watcherId, e);
            return false;
        }
    }

    // HGETALL은 필드와 값이 번갈아 담긴 평평한 배열을 반환한다. 키가 없으면 빈 배열 반환
    private Optional<WatchingPresence> toPresence(UUID watcherId, List<String> flat) {
        if (flat == null || flat.isEmpty()) {
            return Optional.empty();
        }

        Map<String , String> fields = new HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            fields.put(flat.get(i), flat.get(i + 1));
        }

        // 필드가 비면 소유자 없음으로 처리함
        if (!fields.keySet().containsAll(List.of(
            FIELD_SNAPSHOT_ID, FIELD_CONTENT_ID, FIELD_SESSION_ID, FIELD_STARTED_AT))) {
            log.warn("presence 필드가 불완전해 이전 소유자를 복원하지 못함: watcherId={}", watcherId);
            return Optional.empty();
        }

        return Optional.of(new WatchingPresence(
            UUID.fromString(fields.get(FIELD_SNAPSHOT_ID)),
            watcherId,
            UUID.fromString(fields.get(FIELD_CONTENT_ID)),
            fields.get(FIELD_SESSION_ID),
            fields.get(FIELD_SUBSCRIPTION_ID),
            Instant.parse(fields.get(FIELD_STARTED_AT))));
    }

    // DISCONNECT는 프레임에 subscriptionId가 없어 null이 넘어올 수 있음 -> 빈 문자열로 바꿔 넘기면 안전하게 무동작이 됨
    private String nullSafe(String value) {
        return (value == null) ? "" : value;
    }

    private String key(UUID watcherId) {
        return KEY_TEMPLATE.formatted(watcherId);
    }
}
