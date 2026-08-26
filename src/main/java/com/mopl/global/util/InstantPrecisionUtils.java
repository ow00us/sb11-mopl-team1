package com.mopl.global.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * DB 컬럼(TIMESTAMP(6))과 JVM Instant(나노초) 사이 정밀도 차이를 맞추는 유틸리티입니다.
 *
 * pgjdbc는 Instant를 TIMESTAMP(6) 컬럼에 쓸 때 마이크로초 미만 나머지가 500ns 이상이면
 * 다음 마이크로초로 올림한다. 이 유틸리티가 단순 절삭이 아니라
 * 500ns를 더한 후 절삭하는 이유는 그 반올림 규칙과 결과를 맞추기 위해서다 — 그래야 애플리케이션이
 * 메모리에서 계산한 값과 실제로 DB에 저장되는 값이 경계(예: .123456500)에서도 어긋나지
 * 않는다. 단순 절삭으로 바꾸면 이 일치가 깨진다.
 */
public final class InstantPrecisionUtils {

    private InstantPrecisionUtils() {}

    /**
     * 마이크로초 미만을 반올림해 마이크로초 단위로 정규화합니다.
     *
     * @param value 정규화할 값. null을 허용하지 않으며, null이면 즉시 NullPointerException을
     *              던진다 — 이 메서드는 이미 영속화된(따라서 null일 수 없는) 타임스탬프에만
     *              쓰이므로, null을 조용히 통과시키기보다 호출부의 전제 위반을 그 자리에서
     *              드러내는 쪽을 택했다.
     */
    public static Instant normalizeToMicros(Instant value) {
        return value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }
}
