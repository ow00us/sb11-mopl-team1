package com.mopl.watchingsession.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class WatchingSessionSnapshotTest {

    private static final Instant EXPIRES_AT = Instant.parse("2026-08-19T00:00:00Z");

    private WatchingSessionSnapshot snapshot() {
        return WatchingSessionSnapshot.builder()
            .watcherId(UUID.randomUUID())
            .contentId(UUID.randomUUID())
            .expiresAt(EXPIRES_AT)
            .build();
    }

    @Test
    @DisplayName("expiresAt 이전 시각이면 활성으로 판정한다")
    void isActiveAt_returnsTrue_beforeExpiresAt() {
        assertThat(snapshot().isActiveAt(EXPIRES_AT.minusNanos(1_000))).isTrue();
    }

    @Test
    @DisplayName("expiresAt과 정확히 같은 시각은 비활성으로 판정한다")
    void isActiveAt_returnsFalse_atExactExpiresAt() {
        assertThat(snapshot().isActiveAt(EXPIRES_AT)).isFalse();
    }

    @Test
    @DisplayName("expiresAt 이후 시각이면 비활성으로 판정한다")
    void isActiveAt_returnsFalse_afterExpiresAt() {
        assertThat(snapshot().isActiveAt(EXPIRES_AT.plusSeconds(1))).isFalse();
    }
}
