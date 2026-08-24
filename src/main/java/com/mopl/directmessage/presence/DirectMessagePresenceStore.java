package com.mopl.directmessage.presence;

import com.mopl.directmessage.config.DirectMessagePresenceProperties;
import com.mopl.global.realtime.RealtimeInstanceId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DirectMessagePresenceStore {

    private static final String REGISTER_LUA = """
        redis.call(
            'ZADD',
            KEYS[1],
            ARGV[4],
            ARGV[1]
        )
        redis.call(
            'PEXPIRE',
            KEYS[1],
            ARGV[5]
        )
        redis.call(
            'HSET',
            KEYS[2],
            ARGV[2],
            ARGV[3]
        )
        redis.call(
            'PEXPIRE',
            KEYS[2],
            ARGV[5]
        )
        return 1
        """;

    private static final String UNREGISTER_LUA = """
        if redis.call(
            'TYPE',
            KEYS[2]
        )['ok'] ~= 'hash' then
            return 0
        end

        local storedReference =
            redis.call(
                'HGET',
                KEYS[2],
                ARGV[2]
            )

        if storedReference ~= ARGV[3] then
            return 0
        end

        redis.call(
            'ZREM',
            KEYS[1],
            ARGV[1]
        )
        redis.call(
            'HDEL',
            KEYS[2],
            ARGV[2]
        )

        if redis.call(
            'HLEN',
            KEYS[2]
        ) == 0 then
            redis.call(
                'DEL',
                KEYS[2]
            )
        end

        return 1
        """;

    private static final String UNREGISTER_SESSION_LUA = """
        if redis.call(
            'TYPE',
            KEYS[1]
        )['ok'] ~= 'hash' then
            return 0
        end

        local references =
            redis.call(
                'HVALS',
                KEYS[1]
            )

        for index, reference in ipairs(references) do
            local separator =
                string.find(
                    reference,
                    '|',
                    1,
                    true
                )

            if separator then
                local conversationId =
                    string.sub(
                        reference,
                        1,
                        separator - 1
                    )

                local member =
                    string.sub(
                        reference,
                        separator + 1
                    )

                redis.call(
                    'ZREM',
                    ARGV[1] .. conversationId,
                    member
                )
            end
        end

        redis.call(
            'DEL',
            KEYS[1]
        )

        return #references
        """;

    private static final String RENEW_SESSION_LUA = """
        if redis.call(
            'TYPE',
            KEYS[1]
        )['ok'] ~= 'hash' then
            return 0
        end

        local references =
            redis.call(
                'HVALS',
                KEYS[1]
            )

        for index, reference in ipairs(references) do
            local separator =
                string.find(
                    reference,
                    '|',
                    1,
                    true
                )

            if separator then
                local conversationId =
                    string.sub(
                        reference,
                        1,
                        separator - 1
                    )

                local member =
                    string.sub(
                        reference,
                        separator + 1
                    )

                local conversationKey =
                    ARGV[1] .. conversationId

                redis.call(
                    'ZADD',
                    conversationKey,
                    ARGV[2],
                    member
                )
                redis.call(
                    'PEXPIRE',
                    conversationKey,
                    ARGV[3]
                )
            end
        end

        redis.call(
            'PEXPIRE',
            KEYS[1],
            ARGV[3]
        )

        return #references
        """;

    private static final String IS_ACTIVE_LUA = """
        if redis.call(
            'TYPE',
            KEYS[1]
        )['ok'] ~= 'zset' then
            return 0
        end

        redis.call(
            'ZREMRANGEBYSCORE',
            KEYS[1],
            '-inf',
            ARGV[1]
        )

        return redis.call(
            'ZCARD',
            KEYS[1]
        )
        """;

    private static final RedisScript<Long>
        REGISTER_SCRIPT =
        new DefaultRedisScript<>(
            REGISTER_LUA,
            Long.class
        );

    private static final RedisScript<Long>
        UNREGISTER_SCRIPT =
        new DefaultRedisScript<>(
            UNREGISTER_LUA,
            Long.class
        );

    private static final RedisScript<Long>
        UNREGISTER_SESSION_SCRIPT =
        new DefaultRedisScript<>(
            UNREGISTER_SESSION_LUA,
            Long.class
        );

    private static final RedisScript<Long>
        RENEW_SESSION_SCRIPT =
        new DefaultRedisScript<>(
            RENEW_SESSION_LUA,
            Long.class
        );

    private static final RedisScript<Long>
        IS_ACTIVE_SCRIPT =
        new DefaultRedisScript<>(
            IS_ACTIVE_LUA,
            Long.class
        );

    private final StringRedisTemplate redisTemplate;
    private final RealtimeInstanceId instanceId;
    private final DirectMessagePresenceProperties properties;

    public void register(
        UUID userId,
        UUID conversationId,
        String sessionId,
        String subscriptionId
    ) {
        String member =
            member(
                sessionId,
                subscriptionId
            );

        String subscriptionField =
            DirectMessagePresenceKey.component(
                subscriptionId
            );

        String reference =
            reference(
                conversationId,
                member
            );

        long ttlMillis =
            properties.getTtl()
                .toMillis();

        long expiresAt =
            System.currentTimeMillis()
                + ttlMillis;

        redisTemplate.execute(
            REGISTER_SCRIPT,
            List.of(
                DirectMessagePresenceKey.conversation(
                    userId,
                    conversationId
                ),
                DirectMessagePresenceKey.session(
                    userId,
                    instanceId.value(),
                    sessionId
                )
            ),
            member,
            subscriptionField,
            reference,
            String.valueOf(expiresAt),
            String.valueOf(ttlMillis)
        );
    }

    public boolean unregister(
        UUID userId,
        UUID conversationId,
        String sessionId,
        String subscriptionId
    ) {
        String member =
            member(
                sessionId,
                subscriptionId
            );

        Long result =
            redisTemplate.execute(
                UNREGISTER_SCRIPT,
                List.of(
                    DirectMessagePresenceKey.conversation(
                        userId,
                        conversationId
                    ),
                    DirectMessagePresenceKey.session(
                        userId,
                        instanceId.value(),
                        sessionId
                    )
                ),
                member,
                DirectMessagePresenceKey.component(
                    subscriptionId
                ),
                reference(
                    conversationId,
                    member
                )
            );

        return Long.valueOf(1L)
            .equals(result);
    }

    public long unregisterSession(
        UUID userId,
        String sessionId
    ) {
        Long result =
            redisTemplate.execute(
                UNREGISTER_SESSION_SCRIPT,
                List.of(
                    DirectMessagePresenceKey.session(
                        userId,
                        instanceId.value(),
                        sessionId
                    )
                ),
                DirectMessagePresenceKey
                    .conversationPrefix(userId)
            );

        return result == null
            ? 0L
            : result;
    }

    public long renewSession(
        UUID userId,
        String sessionId
    ) {
        long ttlMillis =
            properties.getTtl()
                .toMillis();

        long expiresAt =
            System.currentTimeMillis()
                + ttlMillis;

        Long result =
            redisTemplate.execute(
                RENEW_SESSION_SCRIPT,
                List.of(
                    DirectMessagePresenceKey.session(
                        userId,
                        instanceId.value(),
                        sessionId
                    )
                ),
                DirectMessagePresenceKey
                    .conversationPrefix(userId),
                String.valueOf(expiresAt),
                String.valueOf(ttlMillis)
            );

        return result == null
            ? 0L
            : result;
    }

    public boolean isActive(
        UUID userId,
        UUID conversationId
    ) {
        Long result =
            redisTemplate.execute(
                IS_ACTIVE_SCRIPT,
                List.of(
                    DirectMessagePresenceKey.conversation(
                        userId,
                        conversationId
                    )
                ),
                String.valueOf(
                    System.currentTimeMillis()
                )
            );

        return result != null
            && result > 0;
    }

    private String member(
        String sessionId,
        String subscriptionId
    ) {
        return DirectMessagePresenceKey.component(
            instanceId.value()
        )
            + ":"
            + DirectMessagePresenceKey.component(
            sessionId
        )
            + ":"
            + DirectMessagePresenceKey.component(
            subscriptionId
        );
    }

    private String reference(
        UUID conversationId,
        String member
    ) {
        return conversationId
            + "|"
            + member;
    }
}
