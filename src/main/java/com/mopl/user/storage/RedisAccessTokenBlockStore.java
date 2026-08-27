package com.mopl.user.storage;

import java.time.Duration;
import java.util.UUID;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 사용자별 Access Token 차단 상태를 Redis에 저장
 *
 * <p>차단 키는 이미 발급된 Access Token의 최대 유효 시간 동안 유지합니다.</p>
 */
@Component
@RequiredArgsConstructor
public class RedisAccessTokenBlockStore
    implements AccessTokenBlockStore {

    private static final String KEY_PREFIX =
        "auth:access-token:blocked-user:";

    private static final String BLOCKED_VALUE =
        "1";

    private static final String ALLOWED_VALUE =
        "0";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void block(
        UUID userId,
        Duration expiration
    ) {
        validateUserId(userId);
        validateExpiration(expiration);

        redisTemplate.opsForValue()
            .set(
                key(userId),
                BLOCKED_VALUE,
                expiration
            );
    }

    @Override
    public Optional<Boolean> findBlocked(
        UUID userId
    ) {
        validateUserId(userId);

        String value =
            redisTemplate.opsForValue()
                .get(key(userId));

        if (value == null) {
            return Optional.empty();
        }

        if (BLOCKED_VALUE.equals(value)) {
            return Optional.of(true);
        }

        if (ALLOWED_VALUE.equals(value)) {
            return Optional.of(false);
        }

        /*
         * 예상하지 못한 값은 인증 허용으로 해석하지 않고
         * DB 재확인이 필요한 캐시 미스로 처리
         */
        return Optional.empty();
    }

    @Override
    public void allowIfAbsent(
        UUID userId,
        Duration expiration
    ) {
        validateUserId(userId);
        validateExpiration(expiration);

        redisTemplate.opsForValue()
            .setIfAbsent(
                key(userId),
                ALLOWED_VALUE,
                expiration
            );
    }

    @Override
    public void unblock(
        UUID userId
    ) {
        validateUserId(userId);

        redisTemplate.delete(
            key(userId)
        );
    }

    private String key(
        UUID userId
    ) {
        return KEY_PREFIX + userId;
    }

    private void validateUserId(
        UUID userId
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "사용자 UUID는 필수입니다."
            );
        }
    }

    private void validateExpiration(
        Duration expiration
    ) {
        if (
            expiration == null
                || expiration.isZero()
                || expiration.isNegative()
        ) {
            throw new IllegalArgumentException(
                "Access Token 차단 만료 시간은 양수여야 합니다."
            );
        }
    }
}
