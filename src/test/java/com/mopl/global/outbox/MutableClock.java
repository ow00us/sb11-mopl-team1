package com.mopl.global.outbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 테스트가 시각을 직접 정하는 {@link Clock} 입니다.
 *
 * <p>relay 는 발행 완료 시각과 다음 시도 시각을 시계에서 읽습니다. 실제 시각을 쓰면 lease
 * 만료와 backoff 검증이 그만큼 기다려야 하고, 대기 시간으로 판정하는 테스트는 환경에 따라
 * 흔들립니다.
 */
class MutableClock extends Clock {

    private volatile Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void set(Instant instant) {
        this.instant = instant;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
