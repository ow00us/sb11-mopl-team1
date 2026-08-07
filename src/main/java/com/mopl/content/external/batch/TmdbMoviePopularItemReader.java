package com.mopl.content.external.batch;

import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.dto.TmdbMovieSummary;
import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import java.util.Collections;
import java.util.Iterator;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemReader;

/**
 * TMDB 인기 영화 목록을 페이지 단위로 순회하며 읽는다.
 * 설정된 최대 페이지에 도달하거나, TMDB가 빈 결과를 반환하면(더 이상 데이터 없음) 종료한다.
 * Job 실행마다 상태(currentPage 등)가 초기화되어야 하므로 반드시 @StepScope 빈으로 등록한다.
 */
@RequiredArgsConstructor
public class TmdbMoviePopularItemReader implements ItemReader<TmdbMovieSummary> {

    private final TmdbApiClient tmdbApiClient;
    private final int maxPages;

    private int currentPage = 1;
    private Iterator<TmdbMovieSummary> currentPageIterator = Collections.emptyIterator();

    @Override
    public TmdbMovieSummary read() {
        while (!currentPageIterator.hasNext()) {
            if (currentPage > maxPages) {
                return null;
            }
            TmdbPopularMoviesResponse response = tmdbApiClient.getPopularMovies(currentPage);
            currentPage++;
            if (response.results().isEmpty()) {
                return null;
            }
            currentPageIterator = response.results().iterator();
        }
        return currentPageIterator.next();
    }
}