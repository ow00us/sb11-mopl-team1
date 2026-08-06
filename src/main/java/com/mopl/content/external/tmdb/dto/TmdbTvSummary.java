package com.mopl.content.external.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TmdbTvSummary(
        long id,
        String name,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("genre_ids") List<Integer> genreIds
) {
}