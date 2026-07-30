package com.mopl.global.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 커서 페이지네이션에서 사용하는 Base64 인코딩/디코딩 유틸리티입니다. */
public final class CursorUtils {

    private CursorUtils() {}

    /** 문자열 값을 Base64로 인코딩합니다. */
    public static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Base64로 인코딩된 커서를 문자열로 디코딩합니다. */
    public static String decode(String cursor) {
        return new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    /** Instant 값을 커서 문자열로 인코딩합니다. ISO-8601 형식으로 마이크로초 정밀도를 보존합니다. */
    public static String encodeInstant(Instant value) {
        return encode(value.toString());
    }

    /** 커서 문자열을 Instant로 디코딩합니다. */
    public static Instant decodeAsInstant(String cursor) {
        return Instant.parse(decode(cursor));
    }

    /** long 값을 커서 문자열로 인코딩합니다. */
    public static String encodeLong(long value) {
        return encode(String.valueOf(value));
    }

    /** 커서 문자열을 long으로 디코딩합니다. */
    public static long decodeAsLong(String cursor) {
        return Long.parseLong(decode(cursor));
    }

    /** BigDecimal 값을 커서 문자열로 인코딩합니다. */
    public static String encodeBigDecimal(BigDecimal value) {
        return encode(value.toPlainString());
    }

    /** 커서 문자열을 BigDecimal로 디코딩합니다. */
    public static BigDecimal decodeAsBigDecimal(String cursor) {
        return new BigDecimal(decode(cursor));
    }

    public record LongPair(long first, long second) {}

    /** long 값 두 개를 하나의 커서 문자열로 인코딩합니다. */
    public static String encodeLongPair(long first, long second) {
        return encode(first + ":" + second);
    }

    /** 커서 문자열을 long 값 두 개로 디코딩합니다. */
    public static LongPair decodeAsLongPair(String cursor) {
        String[] parts = decode(cursor).split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("잘못된 커서 형식입니다.");
        }
        return new LongPair(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }
}