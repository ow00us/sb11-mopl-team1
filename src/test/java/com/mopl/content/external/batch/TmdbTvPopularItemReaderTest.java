package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.TmdbApiException;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;
import com.mopl.content.external.tmdb.dto.TmdbTvSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TmdbTvPopularItemReaderTest {

    private final TmdbApiClient tmdbApiClient = mock(TmdbApiClient.class);

    @Test
    @DisplayName("여러 페이지에 걸친 TV 프로그램을 모두 반환한 뒤 빈 페이지를 만나면 null을 반환한다")
    void read_returnsAllItemsAcrossPages_thenNullWhenPageEmpty() throws Exception {
        TmdbTvSummary tv1 = new TmdbTvSummary(1L, "Tv1", "overview1", "/p1.jpg", List.of(18));
        TmdbTvSummary tv2 = new TmdbTvSummary(2L, "Tv2", "overview2", "/p2.jpg", List.of(35));
        when(tmdbApiClient.getPopularTvShows(1)).thenReturn(new TmdbPopularTvResponse(1, List.of(tv1, tv2), 1));
        when(tmdbApiClient.getPopularTvShows(2)).thenReturn(new TmdbPopularTvResponse(2, List.of(), 1));

        TmdbTvPopularItemReader reader = new TmdbTvPopularItemReader(tmdbApiClient, 5, new SimpleMeterRegistry());

        assertThat(reader.read()).isEqualTo(tv1);
        assertThat(reader.read()).isEqualTo(tv2);
        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("최대 페이지에 도달하면 이후 페이지는 호출하지 않고 종료한다")
    void read_stopsAtMaxPages_withoutCallingFurtherPages() throws Exception {
        TmdbTvSummary tv1 = new TmdbTvSummary(1L, "Tv1", "overview1", "/p1.jpg", List.of(18));
        when(tmdbApiClient.getPopularTvShows(1)).thenReturn(new TmdbPopularTvResponse(1, List.of(tv1), 10));

        TmdbTvPopularItemReader reader = new TmdbTvPopularItemReader(tmdbApiClient, 1, new SimpleMeterRegistry());

        assertThat(reader.read()).isEqualTo(tv1);
        assertThat(reader.read()).isNull();

        verify(tmdbApiClient, never()).getPopularTvShows(eq(2));
    }

    @Test
    @DisplayName("특정 페이지 조회가 실패하면 예외를 던지지 않고 다음 페이지로 넘어가며 실패 카운터가 증가한다")
    void read_pageFetchFails_logsAndContinuesToNextPage() throws Exception {
        TmdbTvSummary tv = new TmdbTvSummary(1L, "Tv1", "overview1", "/p1.jpg", List.of(18));
        when(tmdbApiClient.getPopularTvShows(1)).thenThrow(new TmdbApiException("일시적 장애", null));
        when(tmdbApiClient.getPopularTvShows(2)).thenReturn(new TmdbPopularTvResponse(2, List.of(tv), 2));

        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        TmdbTvPopularItemReader reader = new TmdbTvPopularItemReader(tmdbApiClient, 5, meterRegistry);

        assertThat(reader.read()).isEqualTo(tv);
        assertThat(meterRegistry.counter("external-content-batch.api.failures", "source", "tmdb-tv").count())
                .isEqualTo(1.0);
    }
}