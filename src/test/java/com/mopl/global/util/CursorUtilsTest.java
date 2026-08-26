package com.mopl.global.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("encodeLongPair / decodeAsLongPair 는 두 값을 정확히 복원한다")
    void encodeLongPair_decodesCorrectly() {
        String cursor = CursorUtils.encodeLongPair(100L, 7L);

        CursorUtils.LongPair decoded = CursorUtils.decodeAsLongPair(cursor);

        assertThat(decoded.first()).isEqualTo(100L);
        assertThat(decoded.second()).isEqualTo(7L);
    }

    @Test
    @DisplayName("콜론 구분자가 없는 값을 decodeAsLongPair 하면 예외가 발생한다")
    void decodeAsLongPair_fail_invalidFormat() {
        String cursor = CursorUtils.encode("not-a-pair");

        assertThatThrownBy(() -> CursorUtils.decodeAsLongPair(cursor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("encodePopularCursor / decodeAsPopularCursor 는 count 와 updatedAt(마이크로초 정밀도) 을 정확히 복원한다")
    void encodePopularCursor_decodesToOriginalValues() {
        long subscriberCount = 42L;
        Instant updatedAt = Instant.parse("2026-08-12T10:30:00.123456Z");

        String cursor = CursorUtils.encodePopularCursor(subscriberCount, updatedAt);
        CursorUtils.PopularCursor decoded = CursorUtils.decodeAsPopularCursor(cursor);

        assertThat(decoded.subscriberCount()).isEqualTo(subscriberCount);
        assertThat(decoded.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("콜론 구분자가 없는 값을 decodeAsPopularCursor 하면 IllegalArgumentException 이 발생한다")
    void decodeAsPopularCursor_fail_invalidFormat() {
        String cursor = CursorUtils.encode("no-colon-here");

        assertThatThrownBy(() -> CursorUtils.decodeAsPopularCursor(cursor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("count 가 숫자가 아닌 값을 decodeAsPopularCursor 하면 NumberFormatException 이 발생한다")
    void decodeAsPopularCursor_fail_invalidCount() {
        String cursor = CursorUtils.encode("not-a-number:2026-08-12T10:30:00Z");

        assertThatThrownBy(() -> CursorUtils.decodeAsPopularCursor(cursor))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    @DisplayName("updatedAt 이 ISO-8601 형식이 아닌 값을 decodeAsPopularCursor 하면 DateTimeParseException 이 발생한다")
    void decodeAsPopularCursor_fail_invalidInstant() {
        String cursor = CursorUtils.encode("42:not-an-instant");

        assertThatThrownBy(() -> CursorUtils.decodeAsPopularCursor(cursor))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }
}