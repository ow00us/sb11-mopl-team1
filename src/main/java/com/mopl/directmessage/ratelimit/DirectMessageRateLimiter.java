package com.mopl.directmessage.ratelimit;

import com.mopl.directmessage.config.DirectMessageRateLimitProperties;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectMessageRateLimiter {

    private static final String KEY_PREFIX =
        "mopl:rate-limit:direct-message:";

    private static final String ACQUIRE_LUA = """
        local count = redis.call(
            'INCR',
            KEYS[1]
        )

        if count == 1 then
            redis.call(
                'PEXPIRE',
                KEYS[1],
                ARGV[1]
            )
        end

        return count
        """;

    private static final RedisScript<Long> ACQUIRE_SCRIPT =
        new DefaultRedisScript<>(
            ACQUIRE_LUA,
            Long.class
        );

    private final StringRedisTemplate redisTemplate;
    private final DirectMessageRateLimitProperties properties;

    public boolean tryAcquire(UUID senderId) {
        String key =
            KEY_PREFIX + senderId;

        try {
            Long count =
                redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(key),
                    String.valueOf(
                        properties.getWindow()
                            .toMillis()
                    )
                );

            if (count == null) {
                log.warn(
                    "DM 전송 빈도 제한 결과가 없습니다. senderId={}",
                    senderId
                );

                return true;
            }

            return count <= properties.getMaxMessages();
        } catch (RuntimeException exception) {
            log.warn(
                "DM 전송 빈도 제한을 확인할 수 없어 전송을 허용합니다. senderId={}",
                senderId,
                exception
            );

            return true;
        }
    }
}
