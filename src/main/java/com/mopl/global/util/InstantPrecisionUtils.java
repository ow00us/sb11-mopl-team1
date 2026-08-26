package com.mopl.global.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** DB 컬럼(TIMESTAMP(6))과 JVM Instant(나노초) 사이 정밀도 차이를 맞추는 유틸리티입니다. */
public final class InstantPrecisionUtils {

    private InstantPrecisionUtils() {}

    /** 마이크로초 미만을 반올림해 마이크로초 단위로 정규화합니다. */
    public static Instant normalizeToMicros(Instant value) {
        return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }
}
