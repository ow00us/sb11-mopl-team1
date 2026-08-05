package com.mopl.content.external.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TmdbPopularTvResponse(
        int page,
        List<TmdbTvSummary> results,
        @JsonProperty("total_pages") int totalPages
) {
    public TmdbPopularTvResponse {
        results = results == null ? List.of() : results;
    }

    public static TmdbPopularTvResponse empty() {
        return new TmdbPopularTvResponse(0, List.of(), 0);
    }
}