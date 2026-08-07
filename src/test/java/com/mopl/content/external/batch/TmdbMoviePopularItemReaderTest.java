package com.mopl.content.external.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.dto.TmdbMovieSummary;
import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class TmdbMoviePopularItemReaderTest {

    private final TmdbApiClient tmdbApiClient = mock(TmdbApiClient.class);

    @Test
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
    void read_stopsAtMaxPages_withoutCallingFurtherPages() throws Exception {
        TmdbMovieSummary movie1 = new TmdbMovieSummary(1L, "Movie1", "overview1", "/p1.jpg", List.of(28));
        when(tmdbApiClient.getPopularMovies(1)).thenReturn(new TmdbPopularMoviesResponse(1, List.of(movie1), 10));

        TmdbMoviePopularItemReader reader = new TmdbMoviePopularItemReader(tmdbApiClient, 1);

        assertThat(reader.read()).isEqualTo(movie1);
        assertThat(reader.read()).isNull();

        verify(tmdbApiClient, never()).getPopularMovies(eq(2));
    }
}