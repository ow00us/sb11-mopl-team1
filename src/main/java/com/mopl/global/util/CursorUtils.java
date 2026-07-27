package com.mopl.global.util;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

public final class CursorUtils {

    private CursorUtils() {}

    public static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String cursor) {
        return new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    public static String encodeInstant(Instant value) {
        return encode(String.valueOf(value.toEpochMilli()));
    }

    public static Instant decodeAsInstant(String cursor) {
        return Instant.ofEpochMilli(Long.parseLong(decode(cursor)));
    }

    public static String encodeLong(long value) {
        return encode(String.valueOf(value));
    }

    public static long decodeAsLong(String cursor) {
        return Long.parseLong(decode(cursor));
    }
}