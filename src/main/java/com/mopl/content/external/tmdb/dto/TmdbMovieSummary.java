
package com.mopl.content.external.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record TmdbMovieSummary(
        long id,
        String title,
        String overview,
        @JsonProperty("poster_path") String posterPath,
        @JsonProperty("genre_ids") List<Integer> genreIds
) {
}
