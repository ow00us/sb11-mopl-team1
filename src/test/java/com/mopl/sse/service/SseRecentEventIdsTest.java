package com.mopl.sse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SseRecentEventIdsTest {

    @Test
    @DisplayName("최근 SSE 이벤트 ID 최대 크기는 1 이상이어야 함")
    void constructor_invalidMaxSize_fails() {
        assertThatIllegalArgumentException()
            .isThrownBy(() ->
                new SseRecentEventIds(0)
            );
    }

    @Test
    @DisplayName("동일한 SSE 이벤트 ID는 중복으로 처리")
    void markSeen_sameEvent_returnsFalse() {
        // given
        SseRecentEventIds recentEventIds =
            new SseRecentEventIds(2);

        UUID eventId = UUID.randomUUID();

        // when & then
        assertThat(recentEventIds.markSeen(eventId))
            .isTrue();

        assertThat(recentEventIds.markSeen(eventId))
            .isFalse();
    }

    @Test
    @DisplayName("최대 크기를 넘으면 가장 오래된 SSE 이벤트 ID를 제거")
    void markSeen_exceedsMaxSize_evictsOldest() {
        // given
        SseRecentEventIds recentEventIds =
            new SseRecentEventIds(2);

        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        UUID thirdEventId = UUID.randomUUID();

        // when
        recentEventIds.markSeen(firstEventId);
        recentEventIds.markSeen(secondEventId);
        recentEventIds.markSeen(thirdEventId);

        // then
        assertThat(recentEventIds.markSeen(firstEventId))
            .isTrue();

        assertThat(recentEventIds.markSeen(thirdEventId))
            .isFalse();
    }
}
