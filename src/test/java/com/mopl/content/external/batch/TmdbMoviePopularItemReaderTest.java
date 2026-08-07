package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.TmdbApiException;
import com.mopl.content.external.tmdb.dto.TmdbMovieSummary;
import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TmdbMoviePopularItemReaderTest {

    private final TmdbApiClient tmdbApiClient = mock(TmdbApiClient.class);

    @Test
    @DisplayName("여러 페이지에 걸친 영화를 모두 반환한 뒤 빈 페이지를 만나면 null을 반환한다")
    void read_returnsAllItemsAcrossPages_thenNullWhenPageEmpty() throws Exception {
        TmdbMovieSummary movie1 = new TmdbMovieSummary(1L, "Movie1", "overview1", "/p1.jpg", List.of(28));
        TmdbMovieSummary movie2 = new TmdbMovieSummary(2L, "Movie2", "overview2", "/p2.jpg", List.of(35));
        when(tmdbApiClient.getPopularMovies(1)).thenReturn(new TmdbPopularMoviesResponse(1, List.of(movie1, movie2), 1));
        when(tmdbApiClient.getPopularMovies(2)).thenReturn(new TmdbPopularMoviesResponse(2, List.of(), 1));

        TmdbMoviePopularItemReader reader = new TmdbMoviePopularItemReader(tmdbApiClient, 5);

        assertThat(reader.read()).isEqualTo(movie1);
        assertThat(reader.read()).isEqualTo(movie2);
        assertThat(reader.read()).isNull();
    }

    @Test
    @DisplayName("최대 페이지에 도달하면 이후 페이지는 호출하지 않고 종료한다")
    void read_stopsAtMaxPages_withoutCallingFurtherPages() throws Exception {
        TmdbMovieSummary movie1 = new TmdbMovieSummary(1L, "Movie1", "overview1", "/p1.jpg", List.of(28));
        when(tmdbApiClient.getPopularMovies(1)).thenReturn(new TmdbPopularMoviesResponse(1, List.of(movie1), 10));

        TmdbMoviePopularItemReader reader = new TmdbMoviePopularItemReader(tmdbApiClient, 1);

        assertThat(reader.read()).isEqualTo(movie1);
        assertThat(reader.read()).isNull();

        verify(tmdbApiClient, never()).getPopularMovies(eq(2));
    }

    @Test
    @DisplayName("특정 페이지 조회가 실패하면 예외를 던지지 않고 다음 페이지로 넘어간다")
    void read_pageFetchFails_logsAndContinuesToNextPage() throws Exception {
        TmdbMovieSummary movie = new TmdbMovieSummary(1L, "Movie1", "overview1", "/p1.jpg", List.of(28));
        when(tmdbApiClient.getPopularMovies(1)).thenThrow(new TmdbApiException("일시적 장애", null));
        when(tmdbApiClient.getPopularMovies(2)).thenReturn(new TmdbPopularMoviesResponse(2, List.of(movie), 2));

        TmdbMoviePopularItemReader reader = new TmdbMoviePopularItemReader(tmdbApiClient, 5);

        assertThat(reader.read()).isEqualTo(movie);
    }
}