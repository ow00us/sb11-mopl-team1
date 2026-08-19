package com.mopl.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class InstantPrecisionUtilsTest {

    @Test
    @DisplayName("나노초 500 미만은 마이크로초를 그대로 두고 버림된다")
    void normalizeToMicros_truncates_whenSubMicroRemainderBelow500() {
        Instant value = Instant.parse("2026-08-19T00:00:00.123456499Z");

        assertThat(InstantPrecisionUtils.normalizeToMicros(value))
            .isEqualTo(Instant.parse("2026-08-19T00:00:00.123456Z"));
    }

    @Test
    @DisplayName("나노초 500 이상은 다음 마이크로초로 올림된다")
    void normalizeToMicros_roundsUp_whenSubMicroRemainderAtOrAbove500() {
        Instant value = Instant.parse("2026-08-19T00:00:00.123456789Z");

        assertThat(InstantPrecisionUtils.normalizeToMicros(value))
            .isEqualTo(Instant.parse("2026-08-19T00:00:00.123457Z"));
    }

    @Test
    @DisplayName("나노초 성분이 없으면 값이 그대로 유지된다")
    void normalizeToMicros_isNoOp_whenAlreadyMicroPrecision() {
        Instant value = Instant.parse("2026-08-19T00:00:00.123456Z");

        assertThat(InstantPrecisionUtils.normalizeToMicros(value)).isEqualTo(value);
    }

    @Test
    @DisplayName("나노초 나머지가 정확히 500이면 다음 마이크로초로 올림된다")
    void normalizeToMicros_roundsUp_whenSubMicroRemainderIsExactly500() {
        Instant value = Instant.parse("2026-08-19T00:00:00.123456500Z");

        assertThat(InstantPrecisionUtils.normalizeToMicros(value))
            .isEqualTo(Instant.parse("2026-08-19T00:00:00.123457Z"));
    }
}
