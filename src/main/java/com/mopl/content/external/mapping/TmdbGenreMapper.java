package com.mopl.content.external.mapping;

import java.util.Map;

public class TmdbGenreMapper {

    private static final Map<Integer, String> MOVIE_GENRES = Map.ofEntries(
            Map.entry(28, "Action"), Map.entry(12, "Adventure"), Map.entry(16, "Animation"),
            Map.entry(35, "Comedy"), Map.entry(80, "Crime"), Map.entry(99, "Documentary"),
            Map.entry(18, "Drama"), Map.entry(10751, "Family"), Map.entry(14, "Fantasy"),
            Map.entry(36, "History"), Map.entry(27, "Horror"), Map.entry(10402, "Music"),
            Map.entry(9648, "Mystery"), Map.entry(10749, "Romance"), Map.entry(878, "Science Fiction"),
            Map.entry(10770, "TV Movie"), Map.entry(53, "Thriller"), Map.entry(10752, "War"),
            Map.entry(37, "Western")
    );

    private static final Map<Integer, String> TV_GENRES = Map.ofEntries(
            Map.entry(10759, "Action & Adventure"), Map.entry(16, "Animation"), Map.entry(35, "Comedy"),
            Map.entry(80, "Crime"), Map.entry(99, "Documentary"), Map.entry(18, "Drama"),
            Map.entry(10751, "Family"), Map.entry(10762, "Kids"), Map.entry(9648, "Mystery"),
            Map.entry(10763, "News"), Map.entry(10764, "Reality"), Map.entry(10765, "Sci-Fi & Fantasy"),
            Map.entry(10766, "Soap"), Map.entry(10767, "Talk"), Map.entry(10768, "War & Politics"),
            Map.entry(37, "Western")
    );

    private TmdbGenreMapper() {
    }

    public static String movieGenreName(int genreId) {
        return MOVIE_GENRES.get(genreId);
    }

    public static String tvGenreName(int genreId) {
        return TV_GENRES.get(genreId);
    }
}