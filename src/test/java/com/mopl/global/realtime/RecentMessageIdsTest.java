package com.mopl.global.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RecentMessageIdsTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("중복 기억 용량은 1 이상이어야 한다")
    void rejectsNonPositiveCapacity(int capacity) {
        assertThatThrownBy(() -> new RecentMessageIds(capacity))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxSize");
    }

    @Test
    @DisplayName("용량 상한까지는 모든 ID를 기억하고 중복을 거부한다")
    void retainsAllIdsAtCapacity() {
        RecentMessageIds recent = new RecentMessageIds(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(recent.markSeen(first)).isTrue();
        assertThat(recent.markSeen(first)).isFalse();
        assertThat(recent.markSeen(second)).isTrue();

        assertThat(recent.markSeen(first)).isFalse();
        assertThat(recent.markSeen(second)).isFalse();
    }

    @Test
    @DisplayName("상한을 하나 넘으면 가장 오래된 ID만 잊고 다시 수용한다")
    void evictsOldestIdWhenCapacityIsExceeded() {
        RecentMessageIds recent = new RecentMessageIds(2);
        UUID oldest = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        recent.markSeen(oldest);
        recent.markSeen(second);

        // 중복 수신은 최초 수신 순서를 갱신하지 않습니다.
        assertThat(recent.markSeen(oldest)).isFalse();
        assertThat(recent.markSeen(newest)).isTrue();
        assertThat(recent.markSeen(second)).isFalse();
        assertThat(recent.markSeen(newest)).isFalse();

        assertThat(recent.markSeen(oldest)).isTrue();
        assertThat(recent.markSeen(oldest)).isFalse();
        assertThat(recent.markSeen(newest)).isFalse();
        assertThat(recent.markSeen(second)).isTrue();
    }

    @Test
    @DisplayName("최소 용량 1에서는 새 ID마다 직전 ID만 퇴출한다")
    void supportsSingleEntryCapacity() {
        RecentMessageIds recent = new RecentMessageIds(1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(recent.markSeen(first)).isTrue();
        assertThat(recent.markSeen(first)).isFalse();
        assertThat(recent.markSeen(second)).isTrue();
        assertThat(recent.markSeen(second)).isFalse();
        assertThat(recent.markSeen(first)).isTrue();
    }
}
