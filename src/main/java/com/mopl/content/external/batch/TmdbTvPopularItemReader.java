package com.mopl.content.external.batch;

import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;
import com.mopl.content.external.tmdb.dto.TmdbTvSummary;
import java.util.Collections;
import java.util.Iterator;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.item.ItemReader;

/**
 * TMDB 인기 TV 프로그램 목록을 페이지 단위로 순회하며 읽는다.
 * 설정된 최대 페이지에 도달하거나, TMDB가 빈 결과를 반환하면(더 이상 데이터 없음) 종료한다.
 * Job 실행마다 상태(currentPage 등)가 초기화되어야 하므로 반드시 @StepScope 빈으로 등록한다.
 */
@RequiredArgsConstructor
public class TmdbTvPopularItemReader implements ItemReader<TmdbTvSummary> {

    private final TmdbApiClient tmdbApiClient;
    private final int maxPages;

    private int currentPage = 1;
    private Iterator<TmdbTvSummary> currentPageIterator = Collections.emptyIterator();

    @Override
    public TmdbTvSummary read() {
        while (!currentPageIterator.hasNext()) {
            if (currentPage > maxPages) {
                return null;
            }
            TmdbPopularTvResponse response = tmdbApiClient.getPopularTvShows(currentPage);
            currentPage++;
            if (response.results().isEmpty()) {
                return null;
            }
            currentPageIterator = response.results().iterator();
        }
        return currentPageIterator.next();
    }
}