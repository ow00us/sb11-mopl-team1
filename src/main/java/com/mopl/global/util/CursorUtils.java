package com.mopl.global.util;

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

    /** Instant 값을 커서 문자열로 인코딩합니다. */
    public static String encodeInstant(Instant value) {
        return encode(String.valueOf(value.toEpochMilli()));
    }

    /** 커서 문자열을 Instant로 디코딩합니다. */
    public static Instant decodeAsInstant(String cursor) {
        return Instant.ofEpochMilli(Long.parseLong(decode(cursor)));
    }

    /** long 값을 커서 문자열로 인코딩합니다. */
    public static String encodeLong(long value) {
        return encode(String.valueOf(value));
    }

    /** 커서 문자열을 long으로 디코딩합니다. */
    public static long decodeAsLong(String cursor) {
        return Long.parseLong(decode(cursor));
    }
}