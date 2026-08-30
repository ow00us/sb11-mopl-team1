package com.mopl.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProcessedEventCleanerTest {

    private static final Instant NOW = Instant.parse("2026-08-29T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ProcessedEventRepository repository = mock(ProcessedEventRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    @DisplayName("음수 보존 기간은 저장소 접근과 지표 등록 전에 거부한다")
    void constructor_negativeRetention_rejectsBeforeCleanup() {
        assertThatThrownBy(() -> new ProcessedEventCleaner(
            repository, registry, Duration.ofNanos(-1), 100, CLOCK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("retention");

        verifyNoInteractions(repository);
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("보존 기간이 0이면 고정 시각을 삭제 기준으로 쓰고 빈 결과는 집계하지 않는다")
    void clean_zeroRetention_usesCurrentInstantWithoutCountingEmptyResult() {
        ProcessedEventCleaner cleaner = new ProcessedEventCleaner(
            repository, registry, Duration.ZERO, 100, CLOCK);
        when(repository.deleteRecordedBefore(NOW, 100)).thenReturn(0);

        assertThat(cleaner.clean()).isZero();

        verify(repository).deleteRecordedBefore(NOW, 100);
        assertThat(registry.get("mopl.kafka.processed.cleaned.records").counter().count())
            .isZero();
    }
}
