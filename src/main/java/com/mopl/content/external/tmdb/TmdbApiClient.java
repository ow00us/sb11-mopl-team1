package com.mopl.content.external.tmdb;

import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;

public interface TmdbApiClient {

    TmdbPopularMoviesResponse getPopularMovies(int page);

    TmdbPopularTvResponse getPopularTvShows(int page);
}