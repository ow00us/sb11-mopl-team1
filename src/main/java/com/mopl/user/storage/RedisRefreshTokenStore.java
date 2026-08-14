package com.mopl.user.storage;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Refresh Token Family 세션을 Redis에 저장하는 구현체
 *
 * <p>Redis에는 Refresh Token 원문을 저장하지 않고 다음 정보만
 * 저장합니다.</p>
 *
 * <ul>
 *     <li>로그인 세션을 식별하는 Family ID</li>
 *     <li>세션 소유 사용자 UUID</li>
 *     <li>현재 활성 Refresh Token의 SHA-256 해시</li>
 * </ul>
 *
 * <p>Rotation에서는 Family ID는 유지하고 현재 활성 tokenHash만
 * 새로운 해시로 교체합니다. 로그아웃은 tokenHash가 아닌 Family ID를
 * 기준으로 현재 활성 세션을 폐기합니다.</p>
 */
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore
    implements RefreshTokenStore {

    /**
     * Family 세션 Redis Key 접두어
     *
     * <p>최종 Key 예시:</p>
     *
     * <pre>
     * auth:refresh-token:family:{familyId}
     * </pre>
     *
     * <p>해당 Key는 Redis Hash이며 userId와 현재 활성 tokenHash를
     * 필드로 저장합니다.</p>
     */
    private static final String FAMILY_KEY_PREFIX =
        "auth:refresh-token:family:";

    /**
     * 사용자별 Refresh Token Family 인덱스 Key 접두어
     *
     * <p>최종 Key 예시:</p>
     *
     * <pre>
     * auth:refresh-token:user:{userId}
     * </pre>
     *
     * <p>Redis Set의 Member로 사용자의 Family ID를 저장합니다.</p>
     */
    private static final String USER_FAMILIES_KEY_PREFIX =
        "auth:refresh-token:user:";

    /**
     * Refresh Token SHA-256 해시 형식
     *
     * <p>SHA-256 결과는 32바이트이고, 소문자 16진수로 표현하면
     * 항상 64자가 됩니다.</p>
     */
    private static final Pattern TOKEN_HASH_PATTERN =
        Pattern.compile("^[0-9a-f]{64}$");

    /**
     * 새로운 Family 세션과 사용자별 Family 인덱스를 원자적으로
     * 저장하는 Lua Script
     *
     * <p>Family ID 충돌로 기존 Family Key가 이미 존재하면 기존 세션을
     * 덮어쓰지 않고 0을 반환합니다.</p>
     *
     * <p>KEYS:</p>
     * <ul>
     *     <li>KEYS[1]: Family 세션 Hash Key</li>
     *     <li>KEYS[2]: 사용자별 Family Set Key</li>
     * </ul>
     *
     * <p>ARGV:</p>
     * <ul>
     *     <li>ARGV[1]: 사용자 UUID</li>
     *     <li>ARGV[2]: Family UUID</li>
     *     <li>ARGV[3]: 현재 Refresh Token 해시</li>
     *     <li>ARGV[4]: TTL 밀리초</li>
     * </ul>
     */
    private static final DefaultRedisScript<Long>
        SAVE_FAMILY_SCRIPT =
        new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end

            redis.call(
                'HSET',
                KEYS[1],
                'userId',
                ARGV[1],
                'tokenHash',
                ARGV[3]
            )
            redis.call('PEXPIRE', KEYS[1], ARGV[4])

            redis.call('SADD', KEYS[2], ARGV[2])

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
     * Family ID와 현재 활성 tokenHash가 모두 일치할 때만
     * 세션 소유 사용자 UUID를 반환하는 Lua Script
     *
     * <p>Family Key 조회와 tokenHash 비교를 하나의 Redis 명령으로
     * 처리하여 조회 중간에 Rotation이 끼어드는 것을 방지합니다.</p>
     *
     * <p>KEYS[1]: Family 세션 Hash Key</p>
     * <p>ARGV[1]: 요청 Refresh Token 해시</p>
     */
    private static final DefaultRedisScript<String>
        FIND_USER_SCRIPT =
        new DefaultRedisScript<>(
            """
            local storedTokenHash =
                redis.call('HGET', KEYS[1], 'tokenHash')

            if not storedTokenHash
                or storedTokenHash ~= ARGV[1] then
                return false
            end

            return redis.call(
                'HGET',
                KEYS[1],
                'userId'
            )
            """,
            String.class
        );

    /**
     * 같은 Family의 현재 활성 tokenHash를 새로운 해시로
     * 원자적으로 교체하는 Lua Script
     *
     * <p>사용자 UUID와 기존 tokenHash가 모두 일치할 때만
     * Rotation을 수행합니다.</p>
     *
     * <p>로그아웃과 Rotation이 동시에 실행되더라도 Redis는 Lua Script를
     * 순서대로 실행합니다.</p>
     *
     * <ul>
     *     <li>Rotation이 먼저 실행되면 로그아웃이 같은 Family Key 삭제</li>
     *     <li>로그아웃이 먼저 실행되면 Rotation은 Family Key가 없어 실패</li>
     * </ul>
     *
     * <p>KEYS:</p>
     * <ul>
     *     <li>KEYS[1]: Family 세션 Hash Key</li>
     *     <li>KEYS[2]: 사용자별 Family Set Key</li>
     * </ul>
     *
     * <p>ARGV:</p>
     * <ul>
     *     <li>ARGV[1]: 사용자 UUID</li>
     *     <li>ARGV[2]: Family UUID</li>
     *     <li>ARGV[3]: 기존 tokenHash</li>
     *     <li>ARGV[4]: 새로운 tokenHash</li>
     *     <li>ARGV[5]: 새로운 TTL 밀리초</li>
     * </ul>
     */
    private static final DefaultRedisScript<Long>
        ROTATE_FAMILY_SCRIPT =
        new DefaultRedisScript<>(
            """
            local storedUserId =
                redis.call('HGET', KEYS[1], 'userId')
            local storedTokenHash =
                redis.call('HGET', KEYS[1], 'tokenHash')

            if not storedUserId
                or storedUserId ~= ARGV[1]
                or not storedTokenHash
                or storedTokenHash ~= ARGV[3] then
                return 0
            end

            redis.call(
                'HSET',
                KEYS[1],
                'tokenHash',
                ARGV[4]
            )
            redis.call('PEXPIRE', KEYS[1], ARGV[5])

            redis.call('SADD', KEYS[2], ARGV[2])

            local newExpirationMillis = tonumber(ARGV[5])
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
     * Family의 현재 활성 Refresh Token 세션을 폐기하는 Lua Script
     *
     * <p>요청 Cookie의 tokenHash는 사용하지 않습니다. Family Key에 저장된
     * 사용자 UUID가 인증된 사용자와 일치하면 Rotation으로 갱신된 현재
     * 활성 세션까지 함께 폐기합니다.</p>
     *
     * <p>KEYS:</p>
     * <ul>
     *     <li>KEYS[1]: Family 세션 Hash Key</li>
     *     <li>KEYS[2]: 사용자별 Family Set Key</li>
     * </ul>
     *
     * <p>ARGV:</p>
     * <ul>
     *     <li>ARGV[1]: 인증된 사용자 UUID</li>
     *     <li>ARGV[2]: Family UUID</li>
     * </ul>
     */
    private static final DefaultRedisScript<Long>
        REVOKE_FAMILY_SCRIPT =
        new DefaultRedisScript<>(
            """
            local storedUserId =
                redis.call('HGET', KEYS[1], 'userId')

            if not storedUserId
                or storedUserId ~= ARGV[1] then
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
     * 사용자별 인덱스에서 실제 Family Key가 존재하는 Family ID만
     * 반환하고 만료된 ID는 Set에서 제거하는 Lua Script
     *
     * <p>Redis Set의 각 Member에는 개별 TTL을 설정할 수 없으므로
     * Family Key가 만료된 뒤 Set에 남은 ID를 조회 시점에 정리합니다.</p>
     *
     * <p>KEYS[1]: 사용자별 Family Set Key</p>
     * <p>ARGV[1]: Family Key 접두어</p>
     */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List>
        FIND_ACTIVE_FAMILIES_SCRIPT =
        new DefaultRedisScript<>(
            """
            local familyIds =
                redis.call('SMEMBERS', KEYS[1])
            local activeFamilyIds = {}

            for _, familyId in ipairs(familyIds) do
                local familyKey =
                    ARGV[1] .. familyId

                if redis.call('EXISTS', familyKey) == 1 then
                    table.insert(
                        activeFamilyIds,
                        familyId
                    )
                else
                    redis.call(
                        'SREM',
                        KEYS[1],
                        familyId
                    )
                end
            end

            return activeFamilyIds
            """,
            List.class
        );

    /**
     * Redis 문자열 및 Lua Script 실행에 사용하는 Template
     */
    private final StringRedisTemplate redisTemplate;

    /**
     * 새로운 Refresh Token Family 세션을 저장
     */
    @Override
    public void save(
        UUID userId,
        UUID familyId,
        String tokenHash,
        Duration expiration
    ) {
        validateSaveArguments(
            userId,
            familyId,
            tokenHash,
            expiration
        );

        Long result =
            redisTemplate.execute(
                SAVE_FAMILY_SCRIPT,
                List.of(
                    familyKey(familyId),
                    userFamiliesKey(userId)
                ),
                userId.toString(),
                familyId.toString(),
                tokenHash,
                Long.toString(
                    expiration.toMillis()
                )
            );

        /*
         * UUID 충돌 또는 기존 Family Key가 존재하는 상황에서
         * 기존 인증 세션을 덮어쓰지 않습니다.
         */
        if (!Long.valueOf(1L).equals(result)) {
            throw new IllegalStateException(
                "Refresh Token Family 세션을 저장하지 못했습니다."
            );
        }
    }

    /**
     * Family ID와 tokenHash가 모두 일치하는 세션의 사용자 UUID를 조회
     */
    @Override
    public Optional<UUID> findUserIdByFamilyAndTokenHash(
        UUID familyId,
        String tokenHash
    ) {
        if (familyId == null
            || !isValidTokenHash(tokenHash)) {
            return Optional.empty();
        }

        String storedUserId =
            redisTemplate.execute(
                FIND_USER_SCRIPT,
                List.of(
                    familyKey(familyId)
                ),
                tokenHash
            );

        if (storedUserId == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                UUID.fromString(storedUserId)
            );
        } catch (IllegalArgumentException exception) {
            /*
             * 서버가 저장한 userId가 UUID가 아니라면 인증 실패가 아닌
             * Redis 저장 데이터 손상 상태이므로 서버 예외로 처리
             */
            throw new IllegalStateException(
                "Redis Refresh Token Family의 사용자 UUID 형식이 올바르지 않습니다.",
                exception
            );
        }
    }

    /**
     * 같은 Family의 현재 활성 Refresh Token 해시를 교체
     */
    @Override
    public boolean rotate(
        UUID userId,
        UUID familyId,
        String oldTokenHash,
        String newTokenHash,
        Duration expiration
    ) {
        validateRotateArguments(
            userId,
            familyId,
            oldTokenHash,
            newTokenHash,
            expiration
        );

        Long result =
            redisTemplate.execute(
                ROTATE_FAMILY_SCRIPT,
                List.of(
                    familyKey(familyId),
                    userFamiliesKey(userId)
                ),
                userId.toString(),
                familyId.toString(),
                oldTokenHash,
                newTokenHash,
                Long.toString(
                    expiration.toMillis()
                )
            );

        return Long.valueOf(1L).equals(result);
    }

    /**
     * 인증된 사용자가 소유한 Refresh Token Family를 폐기
     */
    @Override
    public boolean revoke(
        UUID userId,
        UUID familyId
    ) {
        validateRevokeArguments(
            userId,
            familyId
        );

        Long result =
            redisTemplate.execute(
                REVOKE_FAMILY_SCRIPT,
                List.of(
                    familyKey(familyId),
                    userFamiliesKey(userId)
                ),
                userId.toString(),
                familyId.toString()
            );

        return Long.valueOf(1L).equals(result);
    }

    /**
     * 사용자가 보유한 현재 활성 Family ID를 조회
     */
    @Override
    @SuppressWarnings("unchecked")
    public Set<UUID> findFamilyIdsByUserId(
        UUID userId
    ) {
        if (userId == null) {
            return Set.of();
        }

        List<String> activeFamilyIdValues =
            redisTemplate.execute(
                FIND_ACTIVE_FAMILIES_SCRIPT,
                List.of(
                    userFamiliesKey(userId)
                ),
                FAMILY_KEY_PREFIX
            );

        if (activeFamilyIdValues == null
            || activeFamilyIdValues.isEmpty()) {
            return Set.of();
        }

        Set<UUID> familyIds =
            new HashSet<>();

        for (String familyIdValue
            : activeFamilyIdValues) {
            try {
                familyIds.add(
                    UUID.fromString(familyIdValue)
                );
            } catch (IllegalArgumentException exception) {
                /*
                 * 사용자별 인덱스에는 UUID 형식의 Family ID만
                 * 저장돼야 하므로 다른 값은 데이터 손상으로 처리
                 */
                throw new IllegalStateException(
                    "Redis Refresh Token Family ID 형식이 올바르지 않습니다.",
                    exception
                );
            }
        }

        return Set.copyOf(familyIds);
    }

    /**
     * Family 저장 인자를 검증
     */
    private void validateSaveArguments(
        UUID userId,
        UUID familyId,
        String tokenHash,
        Duration expiration
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "Refresh Token 사용자 UUID는 null일 수 없습니다."
            );
        }

        if (familyId == null) {
            throw new IllegalArgumentException(
                "Refresh Token Family ID는 null일 수 없습니다."
            );
        }

        validateTokenHash(tokenHash);

        /*
         * 양수 Duration이더라도 밀리초 미만이면 Redis PX 값은 0이 된다.
         * 따라서 실제 Redis TTL로 사용할 수 있는 최소 1ms 이상인지 함께 확인
         */
        if (expiration == null
            || expiration.isZero()
            || expiration.isNegative()
            || expiration.toMillis() <= 0) {
            throw new IllegalArgumentException(
                "Refresh Token 만료 시간은 1밀리초 이상이어야 합니다."
            );
        }
    }

    /**
     * Rotation 인자를 검증합니다.
     */
    private void validateRotateArguments(
        UUID userId,
        UUID familyId,
        String oldTokenHash,
        String newTokenHash,
        Duration expiration
    ) {
        validateSaveArguments(
            userId,
            familyId,
            newTokenHash,
            expiration
        );

        validateTokenHash(oldTokenHash);

        if (oldTokenHash.equals(newTokenHash)) {
            throw new IllegalArgumentException(
                "기존 Refresh Token 해시와 새로운 해시는 달라야 합니다."
            );
        }
    }

    /**
     * Family 폐기 인자를 검증
     */
    private void validateRevokeArguments(
        UUID userId,
        UUID familyId
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "Refresh Token 사용자 UUID는 null일 수 없습니다."
            );
        }

        if (familyId == null) {
            throw new IllegalArgumentException(
                "Refresh Token Family ID는 null일 수 없습니다."
            );
        }
    }

    /**
     * tokenHash가 SHA-256 소문자 16진수 형식인지 검증
     */
    private void validateTokenHash(
        String tokenHash
    ) {
        if (!isValidTokenHash(tokenHash)) {
            throw new IllegalArgumentException(
                "Refresh Token 해시 형식이 올바르지 않습니다."
            );
        }
    }

    /**
     * tokenHash 형식 검증 결과를 반환
     */
    private boolean isValidTokenHash(
        String tokenHash
    ) {
        return tokenHash != null
            && TOKEN_HASH_PATTERN
            .matcher(tokenHash)
            .matches();
    }

    /**
     * Family 세션 Redis Key를 생성
     */
    private String familyKey(
        UUID familyId
    ) {
        return FAMILY_KEY_PREFIX
            + familyId;
    }

    /**
     * 사용자별 Family 인덱스 Redis Key를 생성
     */
    private String userFamiliesKey(
        UUID userId
    ) {
        return USER_FAMILIES_KEY_PREFIX
            + userId;
    }
}
