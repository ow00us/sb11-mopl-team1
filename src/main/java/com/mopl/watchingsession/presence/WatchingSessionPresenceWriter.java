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
 * <p>
 * presence 키는 "지금 이 사용자의 시청 세션을 어느 WebSocket 연결·구독이 소유하는가"의 원본입니다. 소유권 비교와 삭제·연장 사이에 다른 요청이 끼어들면 낡은
 * 구독이 현재 세션을 지우는 문제가 재발하므로, 비교와 실행을 Lua 스크립트 하나로 묶어 원자적으로 처리합니다. Redis는 스크립트 실행 도중 다른 클라이언트의 명령을
 * 끼워넣지 않습니다.
 * <p>
 * 값을 Hash로 저장해 Lua가 HGET으로 필드를 직접 읽게 합니다. 배포 직후에는 이전 버전이 남긴 문자열(JSON) 타입 키가 섞여 있을 수 있어, 모든 스크립트가
 * HGETALL/HGET을 호출하기 전에 TYPE을 먼저 확인합니다. hash가 아니면(레거시 문자열 또는 키 없음) "활성 세션 없음"과 동일하게 처리합니다 — 이전 형식을
 * 굳이 파싱해 되살리지 않고, 다음 start()가 자연스럽게 새 Hash로 덮어씁니다.
 * <p>
 * 인자·필드가 모두 평문 문자열이라 StringRedisTemplate을 사용합니다. JSON 값 직렬화기를 쓰는 RedisTemplate<String, Object>로
 * ARGV를 넘기면 따옴표가 붙어 문자열 비교가 항상 실패합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WatchingSessionPresenceWriter {

    public record DeletedSnapshot(UUID snapshotId, Instant snapshotUpdatedAt) {

    }

    /**
     * presence TTL 갱신 시도의 판정 결과.
     * <p>
     * 호출자가 "재수립해도 되는 실패"와 "재수립하면 안 되는 실패"를 구분할 수 있어야 하므로 boolean 대신 네 갈래로 반환한다. RENEWED 외에는 모두 갱신이
     * 일어나지 않은 상태다.
     */
    public enum RenewResult {
        /**
         * 소유권이 일치해 TTL이 연장됨
         */
        RENEWED,
        /**
         * presence 키 자체가 없음(TTL 만료) 또는 레거시 문자열 키 - DB 기준 재수립 후보
         */
        KEY_MISSING,
        /**
         * 키는 살아있으나 소유자가 다른 연결/구독 - 재수립하면 남의 소유권을 빼앗게 됨
         */
        OWNER_MISMATCH,
        /**
         * Redis 실패·예상 못한 응답. 현재 소유 상태를 알 수 없으므로 아무것도 하지 않는다
         */
        FAILED
    }

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
        local newContentId = ARGV[2]
        local watcherId = ARGV[7]
        local expiresAt = ARGV[8]
        local previous = {}
        if redis.call('TYPE', key)['ok'] == 'hash' then
          previous = redis.call('HGETALL', key)
        else
          redis.call('DEL', key)
        end

        for i = 1, #previous, 2 do
          if previous[i] == 'contentId' and previous[i + 1] ~= newContentId then
            local oldContentKey = 'mopl:presence:content:' .. previous[i + 1]
            redis.call('ZREM', oldContentKey, watcherId)
            local oldMax = redis.call('ZREVRANGE', oldContentKey, 0, 0, 'WITHSCORES')
            if oldMax[2] then
              redis.call('PEXPIREAT', oldContentKey, oldMax[2])
            end
          end
        end

        redis.call('HSET', key,
          'snapshotId', ARGV[1], 'contentId', ARGV[2],
          'sessionId', ARGV[3], 'subscriptionId', ARGV[4], 'startedAt', ARGV[5],
           'snapshotUpdatedAt', ARGV[6])
        redis.call('PEXPIREAT', key, expiresAt)

        local contentKey = 'mopl:presence:content:' .. newContentId
        redis.call('ZADD', contentKey, expiresAt, watcherId)
        local newMax = redis.call('ZREVRANGE', contentKey, 0, 0, 'WITHSCORES')
        if newMax[2] then
          redis.call('PEXPIREAT', contentKey, newMax[2])
        end

        return previous
        """;

    // 복구 전용: 키가 없거나(TTL 만료) 레거시 타입일 때만 새 소유자를 기록한다. 이미 hash로
    // 살아있는 presence가 있으면(다른 연결이 그 사이 확보한 소유권) 쓰지 않고 거부한다.
    // 확인과 쓰기가 하나의 스크립트 안에서 원자적으로 실행되므로, findExistingWatcherIds()와
    // swap()을 별도 호출로 나누던 이전 방식과 달리 그 사이 다른 인스턴스가 끼어들 창이 없다.
    private static final String RECOVER_IF_ABSENT_LUA = """
        local key = KEYS[1]
        local type = redis.call('TYPE', key)['ok']
        if type == 'hash' then
          return 0
        end
        if type ~= 'none' then
          redis.call('DEL', key)
        end

        local newContentId = ARGV[2]
        local watcherId = ARGV[7]
        local expiresAt = ARGV[8]

        redis.call('HSET', key,
          'snapshotId', ARGV[1], 'contentId', ARGV[2],
          'sessionId', ARGV[3], 'subscriptionId', ARGV[4], 'startedAt', ARGV[5],
           'snapshotUpdatedAt', ARGV[6])
        redis.call('PEXPIREAT', key, expiresAt)

        local contentKey = 'mopl:presence:content:' .. newContentId
        redis.call('ZADD', contentKey, expiresAt, watcherId)
        local newMax = redis.call('ZREVRANGE', contentKey, 0, 0, 'WITHSCORES')
        if newMax[2] then
          redis.call('PEXPIREAT', contentKey, newMax[2])
        end

        return 1
        """;

    private static final RedisScript<Long> RECOVER_IF_ABSENT_SCRIPT = new DefaultRedisScript<>(
        RECOVER_IF_ABSENT_LUA, Long.class);

    // 키가 없으면 HGET이 false를 반환해 문자열 비교가 실패
    // 반환: {'1', snapshotId, snapshotUpdatedAt} 삭제됨(snapshotUpdatedAt은 presence 세대 토큰, 없으면 '') /
    //             {'0', '', ''} 소유권 불일치(이상 신호) /
    //             {'-1', '', ''} 활성 세션 없음(hash 아님 포함, 정상 흐름)
    private static final String DELETE_IF_OWNER_LUA = """
        if redis.call('TYPE', KEYS[1])['ok'] ~= 'hash' then
          return {'-1', '', ''}
        end
        if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1]
            and redis.call('HGET', KEYS[1], 'subscriptionId') == ARGV[2] then
          local snapshotId = redis.call('HGET', KEYS[1], 'snapshotId')
          local snapshotUpdatedAt = redis.call('HGET', KEYS[1], 'snapshotUpdatedAt')
          local contentId = redis.call('HGET', KEYS[1], 'contentId')
          redis.call('DEL', KEYS[1])
          if contentId then
            local contentKey = 'mopl:presence:content:' .. contentId
            redis.call('ZREM', contentKey, ARGV[3])
            local maxScore = redis.call('ZREVRANGE', contentKey, 0, 0, 'WITHSCORES')
            if maxScore[2] then
              redis.call('PEXPIREAT', contentKey, maxScore[2])
            end
          end
          return {'1', snapshotId, snapshotUpdatedAt or ''}
        end
        return {'0', '', ''}
        """;

    // sessionId만 비교. DISCONNECT처럼 subscriptionId를 알 수 없는(연결 자체가 끊긴) 상황에서
    // "이 연결이 지금도 소유자인가"만 판정하면 되는 경우에 사용한다.
    // 반환 규약은 DELETE_IF_OWNER_LUA와 동일
    private static final String DELETE_IF_OWNER_SESSION_LUA = """
        if redis.call('TYPE', KEYS[1])['ok'] ~= 'hash' then
          return {'-1', '', ''}
        end
        if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1] then
          local snapshotId = redis.call('HGET', KEYS[1], 'snapshotId')
          local snapshotUpdatedAt = redis.call('HGET', KEYS[1], 'snapshotUpdatedAt')
          local contentId = redis.call('HGET', KEYS[1], 'contentId')
          redis.call('DEL', KEYS[1])
          if contentId then
            local contentKey = 'mopl:presence:content:' .. contentId
            redis.call('ZREM', contentKey, ARGV[2])
            local maxScore = redis.call('ZREVRANGE', contentKey, 0, 0, 'WITHSCORES')
            if maxScore[2] then
              redis.call('PEXPIREAT', contentKey, maxScore[2])
            end
          end
          return {'1', snapshotId, snapshotUpdatedAt or ''}
        end
        return {'0', '', ''}
        """;

    // PEXPIREAT는 키가 있을 때만 1을 반환하고 키를 새로 만들지 않아 이미 만료된 presence를 heartbeat가 되살리지 않는다
    // 반환: 1 연장됨 / 0 소유권 불일치 / -1 활성 presence 없음(hash 아님 포함, TTL 만료가 주 원인)
    private static final String RENEW_IF_OWNER_LUA = """
        if redis.call('TYPE', KEYS[1])['ok'] ~= 'hash' then
          return -1
        end
        if redis.call('HGET', KEYS[1], 'sessionId') == ARGV[1]
           and redis.call('HGET', KEYS[1], 'subscriptionId') == ARGV[2] then
          local contentId = redis.call('HGET', KEYS[1], 'contentId')
          local expiresAt = ARGV[4]
          if contentId then
            local contentKey = 'mopl:presence:content:' .. contentId
            redis.call('ZADD', contentKey, expiresAt, ARGV[3])
            local maxScore = redis.call('ZREVRANGE', contentKey, 0, 0, 'WITHSCORES')
            if maxScore[2] then
              redis.call('PEXPIREAT', contentKey, maxScore[2])
            end
          end
          return redis.call('PEXPIREAT', KEYS[1], expiresAt)
        end
        return 0
        """;

    private static final String UPDATE_SNAPSHOT_ID_IF_OWNER_LUA = """
        local key = KEYS[1]
        local sessionId = ARGV[1]
        local subscriptionId = ARGV[2]
        local newSnapshotId = ARGV[3]
        local newSnapshotUpdatedAt = ARGV[4]
        if redis.call('TYPE', key)['ok'] ~= 'hash' then return 0 end
        if redis.call('HGET', key, 'sessionId') ~= sessionId then return 0 end
        if redis.call('HGET', key, 'subscriptionId') ~= subscriptionId then return 0 end
        redis.call('HSET', key, 'snapshotId', newSnapshotId, 'snapshotUpdatedAt', newSnapshotUpdatedAt)
        return 1
        """;

    // DefaultRedisScript는 본문의 SHA1을 캐싱해 EVALSHA로 실행되므로 인스턴스를 재사용한다
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SWAP_SCRIPT = new DefaultRedisScript<>(SWAP_LUA,
        List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DELETE_IF_OWNER_SCRIPT = new DefaultRedisScript<>(
        DELETE_IF_OWNER_LUA, List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DELETE_IF_OWNER_SESSION_SCRIPT = new DefaultRedisScript<>(
        DELETE_IF_OWNER_SESSION_LUA, List.class);
    private static final RedisScript<Long> RENEW_IF_OWNER_SCRIPT = new DefaultRedisScript<>(
        RENEW_IF_OWNER_LUA, Long.class);
    private static final RedisScript<Long> UPDATE_SNAPSHOT_ID_IF_OWNER_SCRIPT = new DefaultRedisScript<>(
        UPDATE_SNAPSHOT_ID_IF_OWNER_LUA, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 새 소유자를 기록하고, 이 호출이 밀어낸 직전 소유자를 반환한다.
     * <p>
     * 실패를 격리하지 않고 그대로 전파한다. presence가 소유권의 원본이므로, 기록 실패를 삼키면 소유자가 없는 상태로 시청이 시작되어 이후 퇴장 처리와 LEAVE
     * 브로드캐스트가 통째로 유실된다. 호출자(start)가 DB 스냅샷을 보상 삭제하고 클라이언트에 실패를 알려야 한다.
     */
    @SuppressWarnings("unchecked")
    public Optional<WatchingPresence> swap(UUID watcherId, UUID snapshotId, UUID contentId,
        String sessionId, String subscriptionId, Instant startedAt, Instant snapshotUpdatedAt,
        Duration ttl) {

        Instant expiresAt = Instant.now().plus(ttl);

        List<String> previous = stringRedisTemplate.execute(SWAP_SCRIPT, List.of(key(watcherId)),
            snapshotId.toString(), contentId.toString(), nullSafe(sessionId),
            nullSafe(subscriptionId), startedAt.toString(), snapshotUpdatedAt.toString(),
            watcherId.toString(), String.valueOf(expiresAt.toEpochMilli()));

        return toPresence(watcherId, previous);
    }

    /**
     * 요청자가 현재 소유자일 때만 presence를 삭제한다.
     *
     * @return 실제로 삭제했다면 그 presence가 가리키던 DB 스냅샷 id. 소유권 불일치·활성 세션 없음·Redis 실패는 전부 빈 Optional.
     */
    public Optional<DeletedSnapshot> deleteIfOwner(UUID watcherId, String sessionId,
        String subscriptionId) {
        try {
            List<String> result = stringRedisTemplate.execute(DELETE_IF_OWNER_SCRIPT,
                List.of(key(watcherId)), nullSafe(sessionId), nullSafe(subscriptionId),
                watcherId.toString());

            return parseDeleteResult(watcherId, result, true); // 구독 단위 종료: 불일치는 경고
        } catch (RuntimeException e) {
            log.error("Presence 소유권 삭제 실패: watcherId={}", watcherId, e);
            return Optional.empty();
        }
    }

    /**
     * 요청자의 sessionId가 현재 소유자일 때만 presence를 삭제한다. subscriptionId는 비교하지 않는다.
     * <p>
     * WebSocket 연결 자체가 끊기는 DISCONNECT 처리 전용이다. 연결이 죽으면 그 연결에 딸린 모든 구독이 함께 죽으므로, "이 sessionId가 지금도
     * 소유자인가"만으로 판정이 충분하다. 반대로 구독이 살아있는 상태(재구독, UNSUBSCRIBE)에서는 이 메서드를 쓰면 안 된다 — 같은 연결 안의 낡은 구독이 방금
     * 갈아치운 새 구독을 지울 수 있다.
     *
     * @return 실제로 삭제했다면 그 presence가 가리키던 DB 스냅샷 id. 소유권 불일치·활성 세션 없음·Redis 실패는 전부 빈 Optional.
     */
    public Optional<DeletedSnapshot> deleteIfOwnerSession(UUID watcherId, String sessionId) {
        try {
            List<String> result = stringRedisTemplate.execute(DELETE_IF_OWNER_SESSION_SCRIPT,
                List.of(key(watcherId)), nullSafe(sessionId), watcherId.toString());

            return parseDeleteResult(watcherId, result, false); // 연결 단위 종료(DISCONNECT): 불일치는 디버그
        } catch (RuntimeException e) {
            log.error("Presence 세션 단위 소유권 삭제 실패: watcherId={}", watcherId, e);
            return Optional.empty();
        }
    }

    /**
     * 요청자가 현재 소유자일 때만 presence TTL을 재설정한다.
     *
     * @return 갱신 성공/키 없음/소유권 불일치/실패를 구분하는 판정 결과. KEY_MISSING만이 DB 스냅샷 기준 재수립의 대상이다.
     */
    @SuppressWarnings("ConstantConditions")
    public RenewResult renewIfOwner(UUID watcherId, String sessionId, String subscriptionId,
        Duration ttl) {
        try {
            Instant expiresAt = Instant.now().plus(ttl);

            Long result = stringRedisTemplate.execute(RENEW_IF_OWNER_SCRIPT,
                List.of(key(watcherId)), nullSafe(sessionId), nullSafe(subscriptionId),
                watcherId.toString(), String.valueOf(expiresAt.toEpochMilli()));

            if (result == null) {
                log.error("Presence TTL 갱신 스크립트가 예상 못한 null을 반환함: watcherId={}", watcherId);
                return RenewResult.FAILED;
            }

            long code = result;
            if (code == 1L) {
                return RenewResult.RENEWED;
            }
            if (code == 0L) {
                log.warn("Presence 소유권 불일치로 TTL 연장 거부: watcherId={}", watcherId);
                return RenewResult.OWNER_MISMATCH;
            }
            if (code == -1L) {
                return RenewResult.KEY_MISSING;
            }

            log.error("Presence TTL 갱신 스크립트가 규약 밖의 값을 반환함: watcherId={}, result={}",
                watcherId, code);
            return RenewResult.FAILED;
        } catch (RuntimeException e) {
            log.error("Presence TTL 갱신 실패: watcherId={}", watcherId, e);
            return RenewResult.FAILED;
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

        if (!fields.keySet().containsAll(
            List.of(FIELD_SNAPSHOT_ID, FIELD_CONTENT_ID, FIELD_SESSION_ID, FIELD_STARTED_AT))) {
            log.warn("presence 필드가 불완전해 이전 소유자를 복원하지 못함: watcherId={}", watcherId);
            return Optional.empty();
        }

        // 구버전이 남긴 레코드는 이 필드가 없으므로 null 유지
        String snapshotUpdatedAtRaw = fields.get(FIELD_SNAPSHOT_UPDATED_AT);
        Instant snapshotUpdatedAt =
            snapshotUpdatedAtRaw == null ? null : Instant.parse(snapshotUpdatedAtRaw);

        return Optional.of(
            new WatchingPresence(UUID.fromString(fields.get(FIELD_SNAPSHOT_ID)), watcherId,
                UUID.fromString(fields.get(FIELD_CONTENT_ID)), fields.get(FIELD_SESSION_ID),
                fields.get(FIELD_SUBSCRIPTION_ID), Instant.parse(fields.get(FIELD_STARTED_AT)),
                snapshotUpdatedAt));
    }

    /**
     * 여러 watcherId의 presence 존재 여부를 파이프라인 한 번으로 확인한다. 스위퍼가 후보마다 개별 EXISTS를 왕복하지 않도록 하는 용도.
     */
    public Set<UUID> findExistingWatcherIds(Collection<UUID> watcherIds) {
        if (watcherIds.isEmpty()) {
            return Set.of();
        }
        List<UUID> ordered = List.copyOf(watcherIds);
        try {
            List<Object> results = stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (UUID watcherId : ordered) {
                        connection.keyCommands()
                            .exists(key(watcherId).getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });

            Set<UUID> existing = new HashSet<>();
            for (int i = 0; i < ordered.size(); i++) {
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
     * 소유권이 일치할 때만 presence의 snapshotId 필드를 새 값으로 교체한다. DB 행이 재생성됐을 때(heartbeat 자가 복구) presence가 그 새
     * 세대를 가리키도록 맞추는 용도.
     */
    public boolean updateSnapshotIdIfOwner(UUID watcherId, String sessionId, String subscriptionId,
        UUID newSnapshotId, Instant newSnapshotUpdatedAt) {
        try {
            Long result = stringRedisTemplate.execute(UPDATE_SNAPSHOT_ID_IF_OWNER_SCRIPT,
                List.of(key(watcherId)), nullSafe(sessionId), nullSafe(subscriptionId),
                newSnapshotId.toString(), newSnapshotUpdatedAt.toString());
            return Long.valueOf(1L).equals(result);
        } catch (RuntimeException e) {
            log.error("Presence snapshotId 갱신 실패: watcherId={}", watcherId, e);
            return false;
        }
    }

    /**
     * heartbeat의 DB 기준 재수립 전용. presence 키가 없거나(TTL 만료) 레거시 타입일 때만
     * 새 소유자를 기록한다.
     *
     * @return 실제로 기록했으면 true. 이미 hash로 살아있는 presence가 있으면(다른 연결이
     *         이미 확보한 소유권) false, Redis 실패도 false.
     */
    public boolean recoverIfAbsent(UUID watcherId, UUID snapshotId, UUID contentId,
        String sessionId, String subscriptionId, Instant startedAt, Instant snapshotUpdatedAt,
        Duration ttl) {
        try {
            Instant expiresAt = Instant.now().plus(ttl);

            Long result = stringRedisTemplate.execute(RECOVER_IF_ABSENT_SCRIPT, List.of(key(watcherId)),
                snapshotId.toString(), contentId.toString(), nullSafe(sessionId),
                nullSafe(subscriptionId), startedAt.toString(), snapshotUpdatedAt.toString(),
                watcherId.toString(), String.valueOf(expiresAt.toEpochMilli()));

            return Long.valueOf(1L).equals(result);
        } catch (RuntimeException e) {
            log.error("Presence DB 기준 재수립 실패: watcherId={}", watcherId, e);
            return false;
        }
    }

    // DISCONNECT는 프레임에 subscriptionId가 없어 null이 넘어올 수 있음 -> 빈 문자열로 바꿔 넘기면 안전하게 무동작이 됨
    private String nullSafe(String value) {
        return (value == null) ? "" : value;
    }

    private String key(UUID watcherId) {
        return WatchingSessionPresenceKey.of(watcherId);
    }

    @SuppressWarnings("ConstantConditions")
    private Optional<DeletedSnapshot> parseDeleteResult(UUID watcherId, List<String> result,
        boolean mismatchAsWarn) {
        if (result == null || result.isEmpty()) {
            log.error("Presence 삭제 스크립트가 빈 응답을 반환함: watcherId={}", watcherId);
            return Optional.empty();
        }

        String code = result.get(0);
        if ("-1".equals(code)) {
            return Optional.empty(); // 활성 세션 없음(TTL 만료 등) - 정상 경로, 로그 없음
        }
        if ("0".equals(code)) {
            if (mismatchAsWarn) {
                log.warn("Presence 소유권 불일치로 삭제 거부: watcherId={}", watcherId);
            } else {
                log.debug("Presence 소유권 불일치로 삭제 거부: watcherId={}", watcherId);
            }
            return Optional.empty();
        }
        if (!"1".equals(code) || result.size() < 3) {
            log.error("Presence 삭제 스크립트 응답 필드가 부족하거나 예상 밖 코드: watcherId={}, result={}",
                watcherId, result);
            return Optional.empty();
        }
        try {
            UUID snapshotId = UUID.fromString(result.get(1));
            Instant snapshotUpdatedAt =
                result.get(2).isEmpty() ? null : Instant.parse(result.get(2));
            return Optional.of(new DeletedSnapshot(snapshotId, snapshotUpdatedAt));
        } catch (RuntimeException e) {
            log.error("Presence 삭제 결과 필드 파싱 실패: watcherId={}", watcherId, e);
            return Optional.empty();
        }
    }
}
