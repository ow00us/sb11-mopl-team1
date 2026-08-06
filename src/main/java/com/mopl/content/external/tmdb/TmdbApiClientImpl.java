package com.mopl.content.external.tmdb;

import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class TmdbApiClientImpl implements TmdbApiClient {

    private final RestClient tmdbRestClient;

    @Override
    public TmdbPopularMoviesResponse getPopularMovies(int page) {
        try {
            TmdbPopularMoviesResponse response = tmdbRestClient.get()
                    .uri("/movie/popular?page={page}", page)
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
                    .uri("/tv/popular?page={page}", page)
                    .retrieve()
                    .body(TmdbPopularTvResponse.class);
            return response == null ? TmdbPopularTvResponse.empty() : response;
        } catch (RestClientException e) {
            throw new TmdbApiException("TMDB popular TV 조회 실패 (page=" + page + ")", e);
        }
    }
}