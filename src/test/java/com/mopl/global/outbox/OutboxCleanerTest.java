package com.mopl.global.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxCleanerTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxMetrics metrics = mock(OutboxMetrics.class);

    @Test
    @DisplayName("음수 보존 기간은 정리기를 만들 때 거부하고 저장소·지표에 접근하지 않는다")
    void constructor_negativeRetention_rejectsBeforeCleanup() {
        assertThatThrownBy(() -> new OutboxCleaner(
            repository, metrics, Duration.ofNanos(-1), 100, CLOCK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retention");

        verifyNoInteractions(repository, metrics);
    }

    @Test
    @DisplayName("보존 기간이 0이면 고정 시각을 그대로 삭제 기준으로 전달한다")
    void clean_zeroRetention_usesCurrentInstantAsThreshold() {
        OutboxCleaner cleaner = new OutboxCleaner(repository, metrics, Duration.ZERO, 100, CLOCK);
        when(repository.deletePublishedBefore(NOW, 100)).thenReturn(0);

        assertThat(cleaner.clean()).isZero();

        verify(repository).deletePublishedBefore(NOW, 100);
        verifyNoInteractions(metrics);
    }
}
