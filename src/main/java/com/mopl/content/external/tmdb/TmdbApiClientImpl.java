package com.mopl.content.external.tmdb;

import com.mopl.content.external.tmdb.dto.TmdbMovieDetail;
import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;
import com.mopl.content.external.tmdb.dto.TmdbTvDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class TmdbApiClientImpl implements TmdbApiClient {

    private final RestClient tmdbRestClient;
    private final TmdbProperties tmdbProperties;

    @Override
    public TmdbPopularMoviesResponse getPopularMovies(int page) {
        try {
            TmdbPopularMoviesResponse response = tmdbRestClient.get()
                    .uri("/movie/popular?page={page}&language={language}", page, tmdbProperties.language())
                    .retrieve()
                    .body(TmdbPopularMoviesResponse.class);
            return response == null ? TmdbPopularMoviesResponse.empty() : response;
        } catch (RestClientException e) {
            throw new TmdbApiException("TMDB popular movies 조회 실패 (page=" + page + ")", e);
        }
    }

    @Override
    public TmdbPopularTvResponse getPopularTvShows(int page) {
        try {
            TmdbPopularTvResponse response = tmdbRestClient.get()
                    .uri("/tv/popular?page={page}&language={language}", page, tmdbProperties.language())
                    .retrieve()
                    .body(TmdbPopularTvResponse.class);
            return response == null ? TmdbPopularTvResponse.empty() : response;
        } catch (RestClientException e) {
            throw new TmdbApiException("TMDB popular TV 조회 실패 (page=" + page + ")", e);
        }
    }

    @Override
    public TmdbMovieDetail getMovieDetail(String movieId) {
        try {
            return tmdbRestClient.get()
                    .uri("/movie/{movieId}?language={language}", movieId, tmdbProperties.language())
                    .retrieve()
                    .body(TmdbMovieDetail.class);
        } catch (RestClientException e) {
            throw new TmdbApiException("TMDB movie detail 조회 실패 (movieId=" + movieId + ")", e);
        }
    }

    @Override
    public TmdbTvDetail getTvDetail(String tvId) {
        try {
            return tmdbRestClient.get()
                    .uri("/tv/{tvId}?language={language}", tvId, tmdbProperties.language())
                    .retrieve()
                    .body(TmdbTvDetail.class);
        } catch (RestClientException e) {
            throw new TmdbApiException("TMDB TV detail 조회 실패 (tvId=" + tvId + ")", e);
        }
    }
}