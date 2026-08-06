package com.mopl.content.external.mapping;

import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.external.tmdb.TmdbProperties;
import com.mopl.content.external.tmdb.dto.TmdbMovieSummary;
import com.mopl.content.external.tmdb.dto.TmdbTvSummary;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TmdbContentMapper {

    private final TmdbProperties tmdbProperties;

    public ExternalContentDraft toDraft(TmdbMovieSummary movie) {
        Set<String> tags = toTagNames(movie.genreIds(), TmdbGenreMapper::movieGenreName);
        return new ExternalContentDraft(
                ContentType.MOVIE,
                ContentSource.TMDB,
                String.valueOf(movie.id()),
                movie.title(),
                movie.overview() == null ? "" : movie.overview(),
                toImageUrl(movie.posterPath()),
                tags
        );
    }

    public ExternalContentDraft toDraft(TmdbTvSummary tv) {
        Set<String> tags = toTagNames(tv.genreIds(), TmdbGenreMapper::tvGenreName);
        return new ExternalContentDraft(
                ContentType.TV_SERIES,
                ContentSource.TMDB,
                String.valueOf(tv.id()),
                tv.name(),
                tv.overview() == null ? "" : tv.overview(),
                toImageUrl(tv.posterPath()),
                tags
        );
    }

    private Set<String> toTagNames(List<Integer> genreIds, IntFunction<String> resolver) {
        if (genreIds == null) {
            return new HashSet<>();
        }
        return genreIds.stream()
                .map(resolver::apply)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private String toImageUrl(String posterPath) {
        if (posterPath == null || posterPath.isBlank()) {
            return null;
        }
        return tmdbProperties.imageBaseUrl() + posterPath;
    }
}