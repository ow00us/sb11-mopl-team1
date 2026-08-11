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
     * ARGV[3]: TTL 밀리초
     *
     * Redis는 Lua Script 전체를 하나의 명령처럼 실행하므로
     * 세션 Key만 저장되고 사용자별 인덱스 저장이 실패하는
     * 부분 저장 상태를 방지할 수 있다.
     */
    private static final DefaultRedisScript<Long> SAVE_SESSION_SCRIPT =
        new DefaultRedisScript<>(
            """
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            redis.call('SADD', KEYS[2], ARGV[2])
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            return 1
            """,
            Long.class
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
     * 사용자별 Redis Set에서 Refresh Token 해시 목록을 조회
     *
     * 해당 사용자의 세션이 없거나 사용자별 Set이 만료됐다면
     * 수정할 수 없는 빈 Set을 반환
     *
     * @param userId 조회할 사용자 UUID
     * @return 사용자의 Refresh Token 해시 집합
     */
    @Override
    public Set<String> findTokenHashesByUserId(
        UUID userId
    ) {
        if (userId == null) {
            return Set.of();
        }

        Set<String> tokenHashes =
            redisTemplate.opsForSet()
                .members(userSessionsKey(userId));

        if (tokenHashes == null || tokenHashes.isEmpty()) {
            return Set.of();
        }

        return Set.copyOf(tokenHashes);
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
