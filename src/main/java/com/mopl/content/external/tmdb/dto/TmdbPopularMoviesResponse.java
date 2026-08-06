package com.mopl.content.external.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TmdbPopularMoviesResponse(
        int page,
        List<TmdbMovieSummary> results,
        @JsonProperty("total_pages") int totalPages
) {
    public TmdbPopularMoviesResponse {
        results = results == null ? List.of() : results;
    }

    public static TmdbPopularMoviesResponse empty() {
        return new TmdbPopularMoviesResponse(0, List.of(), 0);
    }
}