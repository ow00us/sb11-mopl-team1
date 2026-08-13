package com.mopl.user.storage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Refresh Token 세션을 Redis에 저장하는 구현체
 *
 * Refresh Token 원문은 저장하지 않고 SHA-256 해시를 Redis Key와
 * 사용자별 세션 인덱스의 값으로 사용
 *
 * 세션 Key에는 사용자 UUID를 저장하고 Refresh Token 만료 시간만큼 TTL을 적용
 * TTL이 지나면 Redis가 세션 Key를 자동으로 제거
 */
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    /**
     * Refresh Token 해시로 세션을 찾기 위한 Key 접두어
     *
     * 최종 Key 예시:
     * auth:refresh-token:session:{tokenHash}
     */
    private static final String SESSION_KEY_PREFIX =
        "auth:refresh-token:session:";

    /**
     * 사용자 UUID로 해당 사용자의 Refresh Token 해시 목록을 찾기 위한
     * Redis Set Key 접두어
     *
     * 최종 Key 예시:
     * auth:refresh-token:user:{userId}
     */
    private static final String USER_SESSIONS_KEY_PREFIX =
        "auth:refresh-token:user:";

    /**
     * Refresh Token 세션 Key와 사용자별 세션 인덱스를
     * 하나의 원자적인 Redis 작업으로 저장하는 Lua Script
     *
     * KEYS[1]: Refresh Token 세션 Key
     * KEYS[2]: 사용자별 Refresh Token Set Key
     *
     * ARGV[1]: 사용자 UUID 문자열
     * ARGV[2]: Refresh Token 해시
     * ARGV[3]: 새 세션의 TTL 밀리초
     *
     * Redis는 Lua Script 전체를 하나의 명령처럼 실행하므로
     * 세션 Key만 저장되고 사용자별 인덱스 저장이 실패하는
     * 부분 저장 상태를 방지할 수 있다.
     *
     * 사용자별 세션 인덱스의 TTL은 기존 TTL과 새 세션 TTL 중
     * 더 긴 값을 유지한다. 따라서 짧은 세션이 나중에 발급되더라도
     * 기존의 긴 세션보다 인덱스가 먼저 만료되지 않는다.
     */
    private static final DefaultRedisScript<Long> SAVE_SESSION_SCRIPT =
        new DefaultRedisScript<>(
            """
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            redis.call('SADD', KEYS[2], ARGV[2])

            local newExpirationMillis = tonumber(ARGV[3])
            local currentIndexTtl = redis.call('PTTL', KEYS[2])

            if currentIndexTtl < newExpirationMillis then
                redis.call('PEXPIRE', KEYS[2], newExpirationMillis)
            end

            return 1
            """,
            Long.class
        );

    /**
     * 기존 Refresh Token 세션을 새 세션으로 교체하는 Lua Script
     *
     * <p>기존 세션 확인과 삭제, 새 세션 저장을 하나의 Redis 명령으로
     * 실행합니다. 동일한 기존 토큰으로 요청이 동시에 들어오면 첫 번째
     * 요청이 기존 세션을 삭제한 뒤 나머지 요청은 실패합니다.</p>
     *
     * <p>기존 세션에 저장된 사용자 UUID가 요청 사용자 UUID와 다르면
     * 다른 사용자의 세션을 변경하지 않고 실패합니다.</p>
     *
     * KEYS[1]: 기존 Refresh Token 세션 Key
     * KEYS[2]: 사용자별 Refresh Token Set Key
     * KEYS[3]: 새로운 Refresh Token 세션 Key
     *
     * ARGV[1]: 사용자 UUID 문자열
     * ARGV[2]: 기존 Refresh Token 해시
     * ARGV[3]: 새로운 Refresh Token 해시
     * ARGV[4]: 새로운 세션 TTL 밀리초
     *
     * 반환값:
     * 1 - 교체 성공
     * 0 - 기존 세션이 없거나 사용자 불일치 또는 새 세션 Key 충돌
     */
    private static final DefaultRedisScript<Long> ROTATE_SESSION_SCRIPT =
        new DefaultRedisScript<>(
            """
            local storedUserId = redis.call('GET', KEYS[1])

            if not storedUserId or storedUserId ~= ARGV[1] then
                return 0
            end

            if redis.call('EXISTS', KEYS[3]) == 1 then
                return 0
            end

            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[2])

            redis.call('SET', KEYS[3], ARGV[1], 'PX', ARGV[4])
            redis.call('SADD', KEYS[2], ARGV[3])

            local newExpirationMillis = tonumber(ARGV[4])
            local currentIndexTtl = redis.call('PTTL', KEYS[2])

            if currentIndexTtl < newExpirationMillis then
                redis.call(
                    'PEXPIRE',
                    KEYS[2],
                    newExpirationMillis
                )
            end

            return 1
            """,
            Long.class
        );

    /**
     * 특정 Refresh Token 세션을 원자적으로 폐기하는 Lua Script
     *
     * <p>세션 Key에 저장된 사용자 UUID와 요청 사용자의 UUID가 일치할 때만
     * 세션 Key와 사용자별 세션 인덱스의 토큰 해시를 함께 제거합니다.</p>
     *
     * <p>Redis가 Lua Script 전체를 하나의 명령처럼 실행하므로
     * 세션 Key만 삭제되고 사용자별 인덱스에는 해시가 남는
     * 부분 삭제 상태를 방지할 수 있습니다.</p>
     *
     * KEYS[1]: 폐기할 Refresh Token 세션 Key
     * KEYS[2]: 사용자별 Refresh Token Set Key
     *
     * ARGV[1]: 인증된 사용자 UUID 문자열
     * ARGV[2]: 폐기할 Refresh Token 해시
     *
     * 반환값:
     * 1 - 세션 폐기 성공
     * 0 - 세션이 없거나 세션 소유자 불일치
     */
    private static final DefaultRedisScript<Long> REVOKE_SESSION_SCRIPT =
        new DefaultRedisScript<>(
            """
            local storedUserId = redis.call('GET', KEYS[1])

            if not storedUserId or storedUserId ~= ARGV[1] then
                return 0
            end

            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[2])

            if redis.call('SCARD', KEYS[2]) == 0 then
                redis.call('DEL', KEYS[2])
            end

            return 1
            """,
            Long.class
        );

    /**
     * 사용자별 세션 인덱스에서 현재 활성 상태인 Refresh Token 해시만 조회하고,
     * 이미 만료된 세션 해시는 인덱스에서 제거하는 Lua Script
     *
     * <p>Redis Set의 Member에는 개별 TTL을 지정할 수 없습니다.
     * 따라서 개별 세션 Key가 만료되더라도 사용자 Set에는 해당 토큰 해시가
     * 남을 수 있습니다.</p>
     *
     * <p>사용자별 Set의 모든 토큰 해시를 확인하면서 대응하는 세션 Key가
     * 존재하면 활성 목록에 포함하고, 존재하지 않으면 SREM으로 제거합니다.</p>
     *
     * <p>조회와 정리를 하나의 Lua Script 안에서 수행하므로 다른 Redis 명령이
     * 중간에 끼어들지 않는 원자적인 정리 작업이 됩니다.</p>
     *
     * KEYS[1]: 사용자별 Refresh Token Set Key
     * ARGV[1]: Refresh Token 세션 Key 접두어
     */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List>
        FIND_ACTIVE_SESSIONS_SCRIPT =
        new DefaultRedisScript<>(
            """
            local tokenHashes = redis.call('SMEMBERS', KEYS[1])
            local activeTokenHashes = {}

            for _, tokenHash in ipairs(tokenHashes) do
                local sessionKey = ARGV[1] .. tokenHash

                if redis.call('EXISTS', sessionKey) == 1 then
                    table.insert(activeTokenHashes, tokenHash)
                else
                    redis.call('SREM', KEYS[1], tokenHash)
                end
            end

            return activeTokenHashes
            """,
            List.class
        );

    private final StringRedisTemplate redisTemplate;

    /**
     * Refresh Token 세션과 사용자별 세션 인덱스를 Redis에 저장
     *
     * 세션 Key:
     * auth:refresh-token:session:{tokenHash}
     *
     * 세션 Value:
     * 사용자 UUID
     *
     * 사용자별 세션 Set:
     * auth:refresh-token:user:{userId}
     *
     * Set Member:
     * Refresh Token 해시
     *
     * @param userId Refresh Token 소유 사용자 UUID
     * @param tokenHash Refresh Token SHA-256 해시
     * @param expiration Redis Key에 적용할 TTL
     */
    @Override
    public void save(
        UUID userId,
        String tokenHash,
        Duration expiration
    ) {
        validateSaveArguments(
            userId,
            tokenHash,
            expiration
        );

        String sessionKey =
            sessionKey(tokenHash);

        String userSessionsKey =
            userSessionsKey(userId);

        long expirationMillis =
            expiration.toMillis();

        redisTemplate.execute(
            SAVE_SESSION_SCRIPT,
            List.of(
                sessionKey,
                userSessionsKey
            ),
            userId.toString(),
            tokenHash,
            Long.toString(expirationMillis)
        );
    }

    /**
     * Refresh Token 해시로 세션 소유 사용자 UUID를 조회
     *
     * Redis Key가 TTL 만료 또는 로그아웃으로 삭제됐다면
     * Redis 조회 결과가 null이므로 빈 Optional을 반환
     *
     * @param tokenHash Refresh Token SHA-256 해시
     * @return 세션 사용자 UUID
     */
    @Override
    public Optional<UUID> findUserIdByTokenHash(
        String tokenHash
    ) {
        if (tokenHash == null || tokenHash.isBlank()) {
            return Optional.empty();
        }

        String storedUserId =
            redisTemplate.opsForValue()
                .get(sessionKey(tokenHash));

        if (storedUserId == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                UUID.fromString(storedUserId)
            );
        } catch (IllegalArgumentException exception) {
            /*
             * Refresh Token 세션 Value에는 UUID만 저장되어야 한다.
             * 다른 형식의 값이 존재한다면 단순한 미등록 토큰이 아니라
             * 저장 데이터가 손상된 상태이므로 서버 상태 예외로 처리
             */
            throw new IllegalStateException(
                "Redis Refresh Token 세션의 사용자 UUID 형식이 올바르지 않습니다.",
                exception
            );
        }
    }

    /**
     * 기존 Refresh Token 세션을 새로운 Refresh Token 세션으로 교체
     *
     * <p>Lua Script가 기존 세션 확인부터 새 세션 저장까지 원자적으로
     * 수행하므로 동일 토큰을 이용한 중복 재발급을 차단합니다.</p>
     *
     * @param userId Refresh Token 소유 사용자 UUID
     * @param oldTokenHash 기존 Refresh Token SHA-256 해시
     * @param newTokenHash 새로운 Refresh Token SHA-256 해시
     * @param expiration 새로운 Refresh Token 세션 TTL
     * @return 교체에 성공하면 true, 기존 세션이 유효하지 않으면 false
     */
    @Override
    public boolean rotate(
        UUID userId,
        String oldTokenHash,
        String newTokenHash,
        Duration expiration
    ) {
        validateRotateArguments(
            userId,
            oldTokenHash,
            newTokenHash,
            expiration
        );

        Long result =
            redisTemplate.execute(
                ROTATE_SESSION_SCRIPT,
                List.of(
                    sessionKey(oldTokenHash),
                    userSessionsKey(userId),
                    sessionKey(newTokenHash)
                ),
                userId.toString(),
                oldTokenHash,
                newTokenHash,
                Long.toString(expiration.toMillis())
            );

        /*
         * Redis Script 실행 결과가 1인 경우에만 교체 성공
         *
         * null은 Redis 연결 또는 응답 문제로 정상 성공을 확인할 수 없는
         * 상태이므로 false로 처리하여 재발급 성공으로 오인하지 않는다.
         */
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 인증된 사용자가 소유한 Refresh Token 세션을 폐기
     *
     * <p>Lua Script에서 세션 소유자 확인, 세션 Key 삭제와
     * 사용자별 세션 인덱스 정리를 하나의 원자적인 연산으로 처리합니다.</p>
     *
     * <p>세션이 이미 만료되거나 폐기된 경우와 다른 사용자가 소유한
     * 세션인 경우에는 Redis 값을 변경하지 않고 false를 반환합니다.</p>
     *
     * @param userId Refresh Token 세션의 소유 사용자 UUID
     * @param tokenHash 폐기할 Refresh Token SHA-256 해시
     * @return 세션을 폐기했으면 true, 세션이 없거나 소유자가 다르면 false
     */
    @Override
    public boolean revoke(
        UUID userId,
        String tokenHash
    ) {
        validateRevokeArguments(
            userId,
            tokenHash
        );

        Long result =
            redisTemplate.execute(
                REVOKE_SESSION_SCRIPT,
                List.of(
                    sessionKey(tokenHash),
                    userSessionsKey(userId)
                ),
                userId.toString(),
                tokenHash
            );

        /*
         * Redis Script가 명시적으로 1을 반환한 경우에만
         * 실제 세션이 폐기된 것으로 판단
         *
         * 세션이 없거나 소유자가 다르면 0이 반환되며,
         * Redis 실행 결과를 확인할 수 없는 null도 성공으로 처리하지 않는다.
         */
        return Long.valueOf(1L).equals(result);
    }

    /**
     * 사용자별 Redis Set에서 현재 활성 상태인 Refresh Token 해시만 조회
     *
     * <p>Redis Set의 Member에는 개별 TTL을 적용할 수 없으므로
     * 개별 세션 Key가 만료된 이후에도 사용자별 Set에는 만료된 토큰 해시가
     * 남아 있을 수 있습니다.</p>
     *
     * <p>Lua Script에서 각 토큰 해시에 대응하는 세션 Key의 존재 여부를
     * 확인하고, 세션 Key가 없는 만료 해시는 사용자 Set에서 제거합니다.
     * 따라서 반환되는 값에는 현재 세션 Key가 존재하는 토큰 해시만 포함됩니다.</p>
     *
     * @param userId 조회할 사용자 UUID
     * @return 현재 활성 상태인 Refresh Token 해시 집합
     */
    @Override
    @SuppressWarnings("unchecked")
    public Set<String> findTokenHashesByUserId(
        UUID userId
    ) {
        if (userId == null) {
            return Set.of();
        }

        /*
         * Lua Script의 반환값은 Redis의 다중 Bulk 응답이며,
         * StringRedisTemplate이 각 값을 String으로 역직렬화
         */
        List<String> activeTokenHashes =
            redisTemplate.execute(
                FIND_ACTIVE_SESSIONS_SCRIPT,
                List.of(userSessionsKey(userId)),
                SESSION_KEY_PREFIX
            );

        if (activeTokenHashes == null
            || activeTokenHashes.isEmpty()) {
            return Set.of();
        }

        /*
         * 외부 호출자가 저장소 내부 결과를 변경하지 못하도록
         * 수정할 수 없는 Set으로 변환해 반환
         */
        return Set.copyOf(activeTokenHashes);
    }

    /**
     * 저장 인자가 Redis Key와 TTL로 사용 가능한지 검증
     */
    private void validateSaveArguments(
        UUID userId,
        String tokenHash,
        Duration expiration
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "Refresh Token 사용자 UUID는 null일 수 없습니다."
            );
        }

        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException(
                "Refresh Token 해시는 비어 있을 수 없습니다."
            );
        }

        if (expiration == null
            || expiration.isZero()
            || expiration.isNegative()) {
            throw new IllegalArgumentException(
                "Refresh Token 만료 시간은 0보다 커야 합니다."
            );
        }
    }

    /**
     * Refresh Token Rotation에 필요한 인자가 올바른지 검증
     */
    private void validateRotateArguments(
        UUID userId,
        String oldTokenHash,
        String newTokenHash,
        Duration expiration
    ) {
        /*
         * 사용자 UUID, 새로운 해시와 만료 시간은 save()와
         * 동일한 저장 조건을 만족해야 한다.
         */
        validateSaveArguments(
            userId,
            newTokenHash,
            expiration
        );

        if (oldTokenHash == null
            || oldTokenHash.isBlank()) {
            throw new IllegalArgumentException(
                "기존 Refresh Token 해시는 비어 있을 수 없습니다."
            );
        }

        /*
         * 기존 해시와 새 해시가 같으면 기존 토큰을 폐기하지 않고
         * 수명만 연장하는 결과가 되므로 Rotation으로 허용하지 않는다.
         */
        if (oldTokenHash.equals(newTokenHash)) {
            throw new IllegalArgumentException(
                "기존 Refresh Token 해시와 새로운 해시는 달라야 합니다."
            );
        }
    }

    /**
     * Refresh Token 세션 폐기에 필요한 인자가 올바른지 검증
     *
     * @param userId Refresh Token 세션의 소유 사용자 UUID
     * @param tokenHash 폐기할 Refresh Token SHA-256 해시
     */
    private void validateRevokeArguments(
        UUID userId,
        String tokenHash
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "Refresh Token 사용자 UUID는 null일 수 없습니다."
            );
        }

        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException(
                "폐기할 Refresh Token 해시는 비어 있을 수 없습니다."
            );
        }
    }

    /**
     * Refresh Token 해시 조회용 Redis Key를 생성
     */
    private String sessionKey(String tokenHash) {
        return SESSION_KEY_PREFIX + tokenHash;
    }

    /**
     * 사용자별 Refresh Token 목록을 저장하는 Redis Set Key를 생성
     */
    private String userSessionsKey(UUID userId) {
        return USER_SESSIONS_KEY_PREFIX + userId;
    }
}
