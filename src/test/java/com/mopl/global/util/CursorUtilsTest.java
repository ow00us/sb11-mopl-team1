package com.mopl.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CursorUtilsTest {

    @Test
    @DisplayName("encodeInstant / decodeAsInstant 는 마이크로초 정밀도를 보존한다")
    void encodeDecodeInstant_preservesMicrosecondPrecision() {
        Instant original = Instant.parse("2026-07-27T10:00:00.123456Z");

        String cursor = CursorUtils.encodeInstant(original);
        Instant decoded = CursorUtils.decodeAsInstant(cursor);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("encodeLong / decodeAsLong 은 값을 정확히 복원한다")
    void encodeLong_decodesCorrectly() {
        long value = 12345L;

        String cursor = CursorUtils.encodeLong(value);
        long decoded = CursorUtils.decodeAsLong(cursor);

        assertThat(decoded).isEqualTo(value);
    }
}