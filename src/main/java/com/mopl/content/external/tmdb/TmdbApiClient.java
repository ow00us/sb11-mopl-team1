package com.mopl.content.external.tmdb;

import com.mopl.content.external.tmdb.dto.TmdbMovieDetail;
import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;
import com.mopl.content.external.tmdb.dto.TmdbTvDetail;

public interface TmdbApiClient {

    TmdbPopularMoviesResponse getPopularMovies(int page);

    TmdbPopularTvResponse getPopularTvShows(int page);

    TmdbMovieDetail getMovieDetail(String movieId);

    TmdbTvDetail getTvDetail(String tvId);
}
