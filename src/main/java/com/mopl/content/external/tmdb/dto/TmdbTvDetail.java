package com.mopl.content.external.tmdb.dto;

/**
 * TMDB {@code GET /tv/{tvId}} 상세 응답에서 현지화 백필에 필요한 필드만 담는다.
 * 목록 응답 전용인 {@link TmdbTvSummary}(genre_ids 등)와 구조가 달라 별도 record로 둔다.
 */
public record TmdbTvDetail(long id, String name, String overview) {
}
