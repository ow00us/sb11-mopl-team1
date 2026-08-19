package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 재시도 간격과 중단 시점 계산을 검증합니다.
 *
 * <p>계산만 하는 컴포넌트이므로 컨텍스트 없이 직접 만들어 씁니다.
 */
class OutboxRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-15T03:00:00Z");

    private OutboxRetryPolicy policy(int maxAttempts, Duration initial, Duration max, double multiplier) {
        return new OutboxRetryPolicy(maxAttempts, initial, max, multiplier);
    }

    @Test
    @DisplayName("시도할수록 간격이 multiplier 배로 늘어난다")
    void nextAttemptAt_growsByMultiplier() {
        OutboxRetryPolicy policy =
            policy(10, Duration.ofSeconds(5), Duration.ofMinutes(10), 2.0);

        assertThat(policy.nextAttemptAt(1, NOW)).isEqualTo(NOW.plusSeconds(5));
        assertThat(policy.nextAttemptAt(2, NOW)).isEqualTo(NOW.plusSeconds(10));
        assertThat(policy.nextAttemptAt(3, NOW)).isEqualTo(NOW.plusSeconds(20));
    }

    @Test
    @DisplayName("간격은 상한을 넘지 않는다")
    void nextAttemptAt_capsAtMaxBackoff() {
        OutboxRetryPolicy policy =
            policy(100, Duration.ofSeconds(5), Duration.ofSeconds(60), 2.0);

        assertThat(policy.nextAttemptAt(5, NOW)).isEqualTo(NOW.plusSeconds(60));
        assertThat(policy.nextAttemptAt(50, NOW)).isEqualTo(NOW.plusSeconds(60));
    }

    /**
     * 시도 횟수가 커지면 {@code initial * multiplier^n} 이 long 범위를 넘습니다. 그 값을
     * 정수로 먼저 계산하면 오버플로로 간격이 음수가 되고, 다음 시도 시각이 과거가 되어
     * backoff 가 사라집니다.
     */
    @Test
    @DisplayName("시도 횟수가 아주 커도 다음 시도 시각이 과거가 되지 않는다")
    void nextAttemptAt_doesNotOverflow() {
        OutboxRetryPolicy policy =
            policy(Integer.MAX_VALUE, Duration.ofSeconds(5), Duration.ofMinutes(10), 2.0);

        assertThat(policy.nextAttemptAt(1_000, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("누적 시도 횟수가 최대치에 닿으면 재시도를 그만둔다")
    void isExhausted_atMaxAttempts() {
        OutboxRetryPolicy policy =
            policy(3, Duration.ofSeconds(5), Duration.ofMinutes(10), 2.0);

        assertThat(policy.isExhausted(2)).isFalse();
        assertThat(policy.isExhausted(3)).isTrue();
        assertThat(policy.isExhausted(4)).isTrue();
    }

    @Test
    @DisplayName("최대 시도 횟수가 1보다 작으면 거부한다")
    void rejectsNonPositiveMaxAttempts() {
        assertThatThrownBy(() -> policy(0, Duration.ofSeconds(5), Duration.ofMinutes(10), 2.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * multiplier 가 1 보다 작으면 시도할수록 간격이 줄어듭니다. 실패가 이어질수록 더 자주
     * 두드리게 되므로 설정 실수를 기동 시점에 막습니다.
     */
    @Test
    @DisplayName("multiplier가 1보다 작으면 거부한다")
    void rejectsMultiplierBelowOne() {
        assertThatThrownBy(() -> policy(3, Duration.ofSeconds(5), Duration.ofMinutes(10), 0.5))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
