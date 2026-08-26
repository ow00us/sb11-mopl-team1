package com.mopl.global.outbox;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 발행 실패를 언제 다시 시도할지, 언제 그만둘지 정합니다.
 *
 * <p>실패를 즉시 다시 시도하면 브로커 장애처럼 원인이 지속되는 상황에서 같은 레코드를
 * 주기마다 계속 두드립니다. 시도할수록 간격을 늘려 실패한 대상이 정상 레코드의 발행을
 * 밀어내지 않게 합니다.
 *
 * <p>간격에 상한을 둡니다. 상한이 없으면 몇 번 실패한 뒤 다음 시도가 며칠 뒤로 밀려
 * 원인을 고쳐도 발행이 재개되지 않습니다.
 */
@Component
public class OutboxRetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double multiplier;

    public OutboxRetryPolicy(
        @Value("${mopl.outbox.retry.max-attempts}") int maxAttempts,
        @Value("${mopl.outbox.retry.initial-backoff}") Duration initialBackoff,
        @Value("${mopl.outbox.retry.max-backoff}") Duration maxBackoff,
        @Value("${mopl.outbox.retry.multiplier}") double multiplier
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-attempts 는 1 이상이어야 합니다. 실제 " + maxAttempts);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier 는 1 이상이어야 합니다. 실제 " + multiplier);
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.multiplier = multiplier;
    }

    /**
     * 자동 재시도를 그만둘 시점인지 판정합니다.
     *
     * @param attempts 이번 실패까지 포함한 누적 시도 횟수
     */
    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }

    /**
     * 다음 시도 시각을 계산합니다.
     *
     * @param attempts 이번 실패까지 포함한 누적 시도 횟수. 1 이면 첫 실패입니다.
     */
    public Instant nextAttemptAt(int attempts, Instant now) {
        return now.plus(backoff(attempts));
    }

    private Duration backoff(int attempts) {
        // 첫 실패가 initial 이고 이후 multiplier 배씩 늘어납니다.
        double factor = Math.pow(multiplier, Math.max(0, attempts - 1));

        // 시도 횟수가 커지면 곱한 값이 long 범위를 넘습니다. double 로 비교한 뒤 상한을
        // 적용해야 오버플로로 간격이 음수가 되는 일이 없습니다.
        double millis = initialBackoff.toMillis() * factor;
        if (millis >= maxBackoff.toMillis()) {
            return maxBackoff;
        }
        return Duration.ofMillis((long) millis);
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
