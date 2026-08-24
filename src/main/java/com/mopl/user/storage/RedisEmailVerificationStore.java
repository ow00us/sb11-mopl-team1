package com.mopl.user.storage;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 이메일 인증 상태를 Redis에 저장하는 구현체
 *
 * <p>인증 코드 원문은 저장하지 않고 HMAC-SHA256 해시만 저장합니다.
 * 발급 제한, 실패 횟수 증가 및 성공한 코드의 일회성 소비는 Lua Script로
 * 처리하여 동시 요청에서도 원자성을 보장합니다.</p>
 */
@Component
@RequiredArgsConstructor
public class RedisEmailVerificationStore
    implements EmailVerificationStore {

    private static final String VERIFICATION_KEY_PREFIX =
        "auth:local-credential:verification:";

    private static final String COOLDOWN_KEY_PREFIX =
        "auth:local-credential:cooldown:";

    private static final Pattern CODE_HASH_PATTERN =
        Pattern.compile("^[0-9a-f]{64}$");

    /**
     * 재전송 제한을 확인하고 인증 상태를 원자적으로 저장
     *
     * <p>KEYS[1]: 인증 상태 Hash Key</p>
     * <p>KEYS[2]: 재전송 제한 Key</p>
     *
     * <p>ARGV[1]: 정규화된 이메일</p>
     * <p>ARGV[2]: 인증 코드 HMAC</p>
     * <p>ARGV[3]: 인증 상태 TTL 밀리초</p>
     * <p>ARGV[4]: 재전송 제한 TTL 밀리초</p>
     */
    private static final DefaultRedisScript<Long>
        ISSUE_SCRIPT =
        new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end

            redis.call(
                'HSET',
                KEYS[1],
                'email',
                ARGV[1],
                'codeHash',
                ARGV[2],
                'attempts',
                '0'
            )

            redis.call(
                'PEXPIRE',
                KEYS[1],
                ARGV[3]
            )

            redis.call(
                'SET',
                KEYS[2],
                '1',
                'PX',
                ARGV[4]
            )

            return 1
            """,
            Long.class
        );

    /**
     * 인증 코드 검증, 실패 횟수 증가 및 성공 상태 소비를 원자적으로 처리
     *
     * <p>반환값:</p>
     * <ul>
     *     <li>0: 인증 상태 없음 또는 만료</li>
     *     <li>1: 인증 성공 및 상태 소비</li>
     *     <li>2: 인증 실패</li>
     *     <li>3: 최대 실패 횟수 도달 및 상태 폐기</li>
     * </ul>
     */
    private static final DefaultRedisScript<Long>
        CONSUME_SCRIPT =
        new DefaultRedisScript<>(
            """
            local storedEmail =
                redis.call(
                    'HGET',
                    KEYS[1],
                    'email'
                )

            local storedCodeHash =
                redis.call(
                    'HGET',
                    KEYS[1],
                    'codeHash'
                )

            if not storedEmail or not storedCodeHash then
                return 0
            end

            if storedEmail == ARGV[1]
                and storedCodeHash == ARGV[2] then
                redis.call('DEL', KEYS[1])
                return 1
            end

            local attempts =
                redis.call(
                    'HINCRBY',
                    KEYS[1],
                    'attempts',
                    1
                )

            if attempts >= tonumber(ARGV[3]) then
                redis.call('DEL', KEYS[1])
                return 3
            end

            return 2
            """,
            Long.class
        );

    private final StringRedisTemplate redisTemplate;

    @Override
    public EmailVerificationIssueResult issue(
        UUID userId,
        String normalizedEmail,
        String codeHash,
        Duration verificationExpiration,
        Duration resendCooldown
    ) {
        validateIssueArguments(
            userId,
            normalizedEmail,
            codeHash,
            verificationExpiration,
            resendCooldown
        );

        Long result =
            redisTemplate.execute(
                ISSUE_SCRIPT,
                List.of(
                    verificationKey(userId),
                    cooldownKey(userId)
                ),
                normalizedEmail,
                codeHash,
                Long.toString(
                    verificationExpiration.toMillis()
                ),
                Long.toString(
                    resendCooldown.toMillis()
                )
            );

        if (Long.valueOf(1L).equals(result)) {
            return EmailVerificationIssueResult.ISSUED;
        }

        if (Long.valueOf(0L).equals(result)) {
            return EmailVerificationIssueResult.COOLDOWN_ACTIVE;
        }

        throw new IllegalStateException(
            "이메일 인증 상태 저장 결과를 확인할 수 없습니다."
        );
    }

    @Override
    public EmailVerificationConsumeResult consume(
        UUID userId,
        String normalizedEmail,
        String candidateCodeHash,
        int maxAttempts
    ) {
        validateConsumeArguments(
            userId,
            normalizedEmail,
            candidateCodeHash,
            maxAttempts
        );

        Long result =
            redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(
                    verificationKey(userId)
                ),
                normalizedEmail,
                candidateCodeHash,
                Integer.toString(maxAttempts)
            );

        if (result == null) {
            throw new IllegalStateException(
                "이메일 인증 코드 검증 결과를 확인할 수 없습니다."
            );
        }

        return switch (result.intValue()) {
            case 0 ->
                EmailVerificationConsumeResult.NOT_FOUND;
            case 1 ->
                EmailVerificationConsumeResult.VERIFIED;
            case 2 ->
                EmailVerificationConsumeResult.INVALID;
            case 3 ->
                EmailVerificationConsumeResult.ATTEMPTS_EXHAUSTED;
            default ->
                throw new IllegalStateException(
                    "알 수 없는 이메일 인증 코드 검증 결과입니다."
                );
        };
    }

    @Override
    public void deleteByUserId(
        UUID userId
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "이메일 인증 사용자 UUID는 null일 수 없습니다."
            );
        }

        redisTemplate.delete(
            List.of(
                verificationKey(userId),
                cooldownKey(userId)
            )
        );
    }

    private String verificationKey(
        UUID userId
    ) {
        /*
         * 중괄호 안의 사용자 UUID를 Redis Cluster hash tag로 사용
         * 인증 상태 Key와 재전송 제한 Key가 같은 hash slot에 배치되어
         * 다중 Key Lua Script를 Redis Cluster에서도 실행할 수 있다.
         */
        return VERIFICATION_KEY_PREFIX
            + "{"
            + userId
            + "}";
    }

    private String cooldownKey(
        UUID userId
    ) {
        return COOLDOWN_KEY_PREFIX
            + "{"
            + userId
            + "}";
    }

    private void validateIssueArguments(
        UUID userId,
        String normalizedEmail,
        String codeHash,
        Duration verificationExpiration,
        Duration resendCooldown
    ) {
        validateCommonArguments(
            userId,
            normalizedEmail,
            codeHash
        );

        if (
            verificationExpiration == null
                || verificationExpiration.toMillis() < 1
        ) {
            throw new IllegalArgumentException(
                "이메일 인증 상태 만료 시간은 1밀리초 이상이어야 합니다."
            );
        }

        if (
            resendCooldown == null
                || resendCooldown.toMillis() < 1
        ) {
            throw new IllegalArgumentException(
                "이메일 인증 재전송 제한 시간은 1밀리초 이상이어야 합니다."
            );
        }

        if (
            resendCooldown.compareTo(
                verificationExpiration
            ) >= 0
        ) {
            throw new IllegalArgumentException(
                "재전송 제한 시간은 인증 상태 만료 시간보다 짧아야 합니다."
            );
        }
    }

    private void validateConsumeArguments(
        UUID userId,
        String normalizedEmail,
        String codeHash,
        int maxAttempts
    ) {
        validateCommonArguments(
            userId,
            normalizedEmail,
            codeHash
        );

        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                "이메일 인증 최대 시도 횟수는 1 이상이어야 합니다."
            );
        }
    }

    private void validateCommonArguments(
        UUID userId,
        String normalizedEmail,
        String codeHash
    ) {
        if (userId == null) {
            throw new IllegalArgumentException(
                "이메일 인증 사용자 UUID는 null일 수 없습니다."
            );
        }

        if (
            normalizedEmail == null
                || normalizedEmail.isBlank()
        ) {
            throw new IllegalArgumentException(
                "이메일 인증 대상 이메일은 비어 있을 수 없습니다."
            );
        }

        if (
            codeHash == null
                || !CODE_HASH_PATTERN
                .matcher(codeHash)
                .matches()
        ) {
            throw new IllegalArgumentException(
                "이메일 인증 코드 해시는 64자의 소문자 16진수여야 합니다."
            );
        }
    }
}
