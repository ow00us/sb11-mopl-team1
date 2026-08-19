package com.mopl.watchingsession.presence;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
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
 * 값을 Hash로 저장해 Lua가 HGET으로 필드를 직접 읽게 합니다. 배포 직후에는 이전 버전이
 * 남긴 문자열(JSON) 타입 키가 섞여 있을 수 있어, 모든 스크립트가 HGETALL/HGET을 호출하기
 * 전에 TYPE을 먼저 확인합니다. hash가 아니면(레거시 문자열 또는 키 없음) "활성 세션 없음"과
 * 동일하게 처리합니다 — 이전 형식을 굳이 파싱해 되살리지 않고, 다음 start()가 자연스럽게
 * 새 Hash로 덮어씁니다.
 *
 * 인자·필드가 모두 평문 문자열이라 StringRedisTemplate을 사용합니다.
 * JSON 값 직렬화기를 쓰는 RedisTemplate<String, Object>로 ARGV를 넘기면 따옴표가 붙어
 * 문자열 비교가 항상 실패합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionPresenceWriter {

    public record DeletedSnapshot(UUID snapshotId, Instant snapshotUpdatedAt) {}

    private static final String KEY_TEMPLATE = "mopl:presence:watcher:%s";
    private static final String FIELD_SNAPSHOT_ID = "snapshotId";
    private static final String FIELD_CONTENT_ID = "contentId";
    private static final String FIELD_SESSION_ID = "sessionId";
    private static final String FIELD_SUBSCRIPTION_ID = "subscriptionId";
    private static final String FIELD_STARTED_AT = "startedAt";
    private static final String FIELD_SNAPSHOT_UPDATED_AT = "snapshotUpdatedAt";

    // TYPE이 hash일 때만 이전 값을 읽는다. 레거시 문자열 키는 빈 배열로 취급해
    // "직전 소유자 없음"과 동일한 결과를 낸다. DEL은 타입과 무관하게 항상 동작하므로
    // 쓰기 자체는 레거시 키 위에서도 안전하다.
    private static final String SWAP_LUA = """
        local key = KEYS[1]
        local previous = {}
        if redis.call('TYPE', key)['ok'] == 'hash' then
          previous = redis.call('HGETALL', key)
        else
          redis.call('DEL', key)
        end
        redis.call('HSET', key,
          'snapshotId', ARGV[1], 'contentId', ARGV[2],
          'sessionId', ARGV[3], 'subscriptionId', ARGV[4], 'startedAt', ARGV[5],
           'snapshotUpdatedAt', ARGV[7])
        redis.call('PEXPIRE', key, ARGV[6])
        return previous
        """;

    // 키가 없으면 HGET이 false를 반환해 문자열 비교가 실패
    // 반환: {'1', snapshotId} 삭제됨 / {'0', ''} 소유권 불일치(이상 신호) /
    //       {'-1', ''} 활성 세션 없음(hash 아님 포함, 정상 흐름)
    private static final String DELETE_IF_OWNER_LUA =  """
      if redis.call('TYPE', KEYS[1])['ok'] ~= 'hash' then
        return {'-1', '', ''}
      end
      if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1]
          and redis.call('HGET', KEYS[1], 'subscriptionId') == ARGV[2] then
        local snapshotId = redis.call('HGET', KEYS[1], 'snapshotId')
        local snapshotUpdatedAt = redis.call('HGET', KEYS[1], 'snapshotUpdatedAt')
        redis.call('DEL', KEYS[1])
        return {'1', snapshotId, snapshotUpdatedAt or ''}
      end
      return {'0', '', ''}
      """;

    // sessionId만 비교. DISCONNECT처럼 subscriptionId를 알 수 없는(연결 자체가 끊긴) 상황에서
    // "이 연결이 지금도 소유자인가"만 판정하면 되는 경우에 사용한다.
    // 반환 규약은 DELETE_IF_OWNER_LUA와 동일
    private static final String DELETE_IF_OWNER_SESSION_LUA = """
        if redis.call('TYPE', KEYS[1])['ok'] ~= 'hash' then
          return {'-1', ''}
        end
        if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1] then
          local snapshotId = redis.call('HGET', KEYS[1], 'snapshotId')
          local snapshotUpdatedAt = redis.call('HGET', KEYS[1], 'snapshotUpdatedAt')
          redis.call('DEL', KEYS[1])
          return {'1', snapshotId, snapshotUpdatedAt or ''}
        end
        return {'0', '', ''}
        """;

    // PEXPIRE는 키가 있을 때만 1을 반환하고 키를 새로 만들지 않아 이미 만료된 presence를 heartbeat가 되살리지 않는다
    private static final String RENEW_IF_OWNER_LUA = """
        if redis.call('TYPE', KEYS[1])['ok'] ~= 'hash' then
          return -1
        end
        if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1]
           and redis.call('HGET', KEYS[1], 'subscriptionId') == ARGV[2] then
          return redis.call('PEXPIRE', KEYS[1], ARGV[3])
        end
        return 0
        """;

    private static final String UPDATE_SNAPSHOT_ID_IF_OWNER_LUA = """
        local key = KEYS[1]
        local sessionId = ARGV[1]
        local subscriptionId = ARGV[2]
        local newSnapshotId = ARGV[3]
        local newSnapshotUpdatedAt = ARGV[4]
        if redis.call('HGET', key, 'sessionId') ~= sessionId then return 0 end
        if redis.call('HGET', key, 'subscriptionId') ~= subscriptionId then return 0 end
        redis.call('HSET', key, 'snapshotId', newSnapshotId, 'snapshotUpdatedAt', newSnapshotUpdatedAt)
        return 1
        """;

    // DefaultRedisScript는 본문의 SHA1을 캐싱해 EVALSHA로 실행되므로 인스턴스를 재사용한다
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SWAP_SCRIPT =
        new DefaultRedisScript<>(SWAP_LUA, List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DELETE_IF_OWNER_SCRIPT =
        new DefaultRedisScript<>(DELETE_IF_OWNER_LUA, List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DELETE_IF_OWNER_SESSION_SCRIPT =
        new DefaultRedisScript<>(DELETE_IF_OWNER_SESSION_LUA, List.class);
    private static final RedisScript<Long> RENEW_IF_OWNER_SCRIPT =
        new DefaultRedisScript<>(RENEW_IF_OWNER_LUA, Long.class);
    private static final RedisScript<Long> UPDATE_SNAPSHOT_ID_IF_OWNER_SCRIPT =
        new DefaultRedisScript<>(UPDATE_SNAPSHOT_ID_IF_OWNER_LUA, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 새 소유자를 기록하고, 이 호출이 밀어낸 직전 소유자를 반환한다.
     *
     * 실패를 격리하지 않고 그대로 전파한다. presence가 소유권의 원본이므로, 기록 실패를 삼키면
     * 소유자가 없는 상태로 시청이 시작되어 이후 퇴장 처리와 LEAVE 브로드캐스트가 통째로 유실된다.
     * 호출자(start)가 DB 스냅샷을 보상 삭제하고 클라이언트에 실패를 알려야 한다.
     */
    @SuppressWarnings("unchecked")
    public Optional<WatchingPresence> swap(UUID watcherId, UUID snapshotId, UUID contentId,
        String sessionId, String subscriptionId, Instant startedAt, Instant snapshotUpdatedAt, Duration ttl) {

        List<String> previous = stringRedisTemplate.execute(
            SWAP_SCRIPT,
            List.of(key(watcherId)),
            snapshotId.toString(),
            contentId.toString(),
            nullSafe(sessionId),
            nullSafe(subscriptionId),
            startedAt.toString(),
            String.valueOf(ttl.toMillis()),
            snapshotUpdatedAt.toString());

        return toPresence(watcherId, previous);
    }

    /**
     * 요청자가 현재 소유자일 때만 presence를 삭제한다.
     *
     * @return 실제로 삭제했다면 그 presence가 가리키던 DB 스냅샷 id. 소유권 불일치·활성 세션
     *         없음·Redis 실패는 전부 빈 Optional.
     */
    public Optional<DeletedSnapshot> deleteIfOwner(UUID watcherId, String sessionId, String subscriptionId) {
        try {
            List<String> result = stringRedisTemplate.execute(
                DELETE_IF_OWNER_SCRIPT,
                List.of(key(watcherId)),
                nullSafe(sessionId),
                nullSafe(subscriptionId));

            return parseDeleteResult(watcherId, result);
        } catch (RuntimeException e) {
            log.error("Presence 소유권 삭제 실패: watcherId={}", watcherId, e);
            return Optional.empty();
        }
    }

    /**
     * 요청자의 sessionId가 현재 소유자일 때만 presence를 삭제한다. subscriptionId는 비교하지 않는다.
     *
     * WebSocket 연결 자체가 끊기는 DISCONNECT 처리 전용이다. 연결이 죽으면 그 연결에 딸린
     * 모든 구독이 함께 죽으므로, "이 sessionId가 지금도 소유자인가"만으로 판정이 충분하다.
     * 반대로 구독이 살아있는 상태(재구독, UNSUBSCRIBE)에서는 이 메서드를 쓰면 안 된다 —
     * 같은 연결 안의 낡은 구독이 방금 갈아치운 새 구독을 지울 수 있다.
     *
     * @return 실제로 삭제했다면 그 presence가 가리키던 DB 스냅샷 id. 소유권 불일치·활성 세션
     *         없음·Redis 실패는 전부 빈 Optional.
     */
    public Optional<DeletedSnapshot> deleteIfOwnerSession(UUID watcherId, String sessionId) {
        try {
            List<String> result = stringRedisTemplate.execute(
                DELETE_IF_OWNER_SESSION_SCRIPT,
                List.of(key(watcherId)),
                nullSafe(sessionId));

            return parseDeleteResult(watcherId, result);
        } catch (RuntimeException e) {
            log.error("Presence 세션 단위 소유권 삭제 실패: watcherId={}", watcherId, e);
            return Optional.empty();
        }
    }

    /**
     * 요청자가 현재 소유자일 때만 presence TTL을 재설정한다.
     *
     * @return 실제로 연장했으면 true. 소유권 불일치·키 없음·Redis 실패는 모두 false.
     */
    @SuppressWarnings("ConstantConditions")
    public boolean renewIfOwner(UUID watcherId, String sessionId, String subscriptionId, Duration ttl) {
        try {
            Long result = stringRedisTemplate.execute(
                RENEW_IF_OWNER_SCRIPT,
                List.of(key(watcherId)),
                nullSafe(sessionId),
                nullSafe(subscriptionId),
                String.valueOf(ttl.toMillis()));

            if (result == null) {
                // 파이프라인/트랜잭션 모드 등에서만 나올 수 있는 응답. 예외 경로(아래 catch)와
                // 원인이 다르므로 "Redis 실패"로 뭉뚱그리지 않고 별도로 남긴다.
                log.error("Presence TTL 갱신 스크립트가 예상 못한 null을 반환함: watcherId={}", watcherId);
                return false;
            }
            if (Long.valueOf(0L).equals(result)) {
                log.warn("Presence 소유권 불일치로 TTL 연장 거부: watcherId={}", watcherId);
            }
            return Long.valueOf(1L).equals(result);
        } catch (RuntimeException e) {
            log.error("Presence TTL 갱신 실패: watcherId={}", watcherId, e);
            return false;
        }
    }

    // HGETALL은 필드와 값이 번갈아 담긴 평평한 배열을 반환한다. 키가 없으면 빈 배열 반환
    @SuppressWarnings("ConstantConditions")
    private Optional<WatchingPresence> toPresence(UUID watcherId, List<String> flat) {
        if (flat == null || flat.isEmpty()) {
            return Optional.empty();
        }

        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            fields.put(flat.get(i), flat.get(i + 1));
        }

        if (!fields.keySet().containsAll(List.of(
            FIELD_SNAPSHOT_ID, FIELD_CONTENT_ID, FIELD_SESSION_ID, FIELD_STARTED_AT))) {
            log.warn("presence 필드가 불완전해 이전 소유자를 복원하지 못함: watcherId={}", watcherId);
            return Optional.empty();
        }

        // 구버전이 남긴 레코드는 이 필드가 없으므로 null 유지
        String snapshotUpdatedAtRaw = fields.get(FIELD_SNAPSHOT_UPDATED_AT);
        Instant snapshotUpdatedAt = snapshotUpdatedAtRaw == null ? null : Instant.parse(snapshotUpdatedAtRaw);

        return Optional.of(new WatchingPresence(
            UUID.fromString(fields.get(FIELD_SNAPSHOT_ID)),
            watcherId,
            UUID.fromString(fields.get(FIELD_CONTENT_ID)),
            fields.get(FIELD_SESSION_ID),
            fields.get(FIELD_SUBSCRIPTION_ID),
            Instant.parse(fields.get(FIELD_STARTED_AT)),
            snapshotUpdatedAt));
    }

    /**
     * 여러 watcherId의 presence 존재 여부를 파이프라인 한 번으로 확인한다.
     * 스위퍼가 후보마다 개별 EXISTS를 왕복하지 않도록 하는 용도.
     */
    public Set<UUID> findExistingWatcherIds(Collection<UUID> watcherIds) {
        if (watcherIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ordered = List.copyOf(watcherIds);
        try {
            List<Object> results = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (UUID watcherId : ordered) {
                    connection.keyCommands().exists(key(watcherId).getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });

            Set<UUID> existing = new HashSet<>();
            for (int i = 0; i < ordered.size() ; i++) {
                if (Boolean.TRUE.equals(results.get(i))) {
                    existing.add(ordered.get(i));
                }
            }
            return existing;
        } catch (RuntimeException e) {
            log.error("Presence 일괄 존재 확인 실패: watcherIdCount={}", ordered.size(), e);
            // 확인 실패 시 전부 존재한다고 간주, 삭제를 보수적으로 건너뜀
            return Set.copyOf(ordered);
        }
    }

    /**
     * 소유권이 일치할 때만 presence의 snapshotId 필드를 새 값으로 교체한다.
     * DB 행이 재생성됐을 때(heartbeat 자가 복구) presence가 그 새 세대를 가리키도록 맞추는 용도.
     */
    public boolean updateSnapshotIdIfOwner(UUID watcherId, String sessionId, String subscriptionId,
        UUID newSnapshotId, Instant newSnapshotUpdatedAt) {
        try {
            Long result = stringRedisTemplate.execute(
                UPDATE_SNAPSHOT_ID_IF_OWNER_SCRIPT,
                List.of(key(watcherId)),
                nullSafe(sessionId),
                nullSafe(subscriptionId),
                newSnapshotId.toString(),
                newSnapshotUpdatedAt.toString());
            return Long.valueOf(1L).equals(result);
        } catch (RuntimeException e) {
            log.error("Presence snapshotId 갱신 실패: watcherId={}", watcherId, e);
            return false;
        }
    }

    // DISCONNECT는 프레임에 subscriptionId가 없어 null이 넘어올 수 있음 -> 빈 문자열로 바꿔 넘기면 안전하게 무동작이 됨
    private String nullSafe(String value) {
        return (value == null) ? "" : value;
    }

    private String key(UUID watcherId) {
        return KEY_TEMPLATE.formatted(watcherId);
    }

    @SuppressWarnings("ConstantConditions")
    private Optional<DeletedSnapshot> parseDeleteResult(UUID watcherId, List<String> result) {
        if (result == null || result.size() < 3 || !"1".equals(result.get(0))) {
            return Optional.empty();
        }
        try {
            UUID snapshotId = UUID.fromString(result.get(1));
            // 구버전 presence(토큰 없이 기록된 레코드)는 빈 문자열 - 세대 미검증 폴백으로 null 유지
            Instant snapshotUpdatedAt = result.get(2).isEmpty() ? null : Instant.parse(result.get(2));
            return Optional.of(new DeletedSnapshot(snapshotId, snapshotUpdatedAt));
        } catch (RuntimeException e) {
            log.warn("Presence 삭제 결과 파싱 실패, 방어적으로 빈 Optional 반환: watcherId={}", watcherId, e);
            return Optional.empty();
        }
    }
}
