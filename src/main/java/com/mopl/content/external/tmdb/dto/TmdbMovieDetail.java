package com.mopl.content.external.tmdb.dto;

/**
 * TMDB {@code GET /movie/{movieId}} 상세 응답에서 현지화 백필에 필요한 필드만 담는다.
 * 목록 응답 전용인 {@link TmdbMovieSummary}(genre_ids 등)와 구조가 달라 별도 record로 둔다.
 */
public record TmdbMovieDetail(long id, String title, String overview) {
}
