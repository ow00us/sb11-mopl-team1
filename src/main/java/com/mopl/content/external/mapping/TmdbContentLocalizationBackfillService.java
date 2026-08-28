package com.mopl.content.external.mapping;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.content.external.tmdb.TmdbApiClient;
import com.mopl.content.external.tmdb.dto.TmdbMovieDetail;
import com.mopl.content.external.tmdb.dto.TmdbTvDetail;
import com.mopl.content.repository.ContentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

/**
 * 이미 저장된 TMDB 소스 콘텐츠의 제목·설명을 상세 엔드포인트로 재조회해 한국어로 갱신한다.
 *
 * <p>기존 수집 배치는 {@code /movie/popular}·{@code /tv/popular} 인기 목록만 순회하므로, 인기
 * 순위에서 밀려난 과거 콘텐츠는 {@code language=ko-KR} 파라미터를 붙여도 재방문되지 않는다.
 * 이 백필이 그 공백을 메운다. 관리자 트리거로만 실행되는 일회성 작업이다.
 *
 * <p>콘텐츠 한 건마다 {@link TmdbContentLocalizer}의 콘텐츠 단위 트랜잭션으로 갱신하므로, 한 건이
 * TMDB 호출 실패(404, 5xx, 타임아웃 등)해도 로그만 남기고 다음 콘텐츠로 넘어간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbContentLocalizationBackfillService {

    private static final int PAGE_SIZE = 100;
    // TMDB 무료 티어 호출 빈도 제한(#12)이 아직 없어서, 순차 호출 사이에 짧은 간격을 둔다.
    private static final long CALL_INTERVAL_MILLIS = 150L;

    private final ContentRepository contentRepository;
    private final TmdbApiClient tmdbApiClient;
    private final TmdbContentLocalizer tmdbContentLocalizer;

    public BackfillResult backfill() {
        int total = 0;
        int updated = 0;
        int failed = 0;

        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        Slice<Content> slice;
        do {
            slice = contentRepository.findBySource(ContentSource.TMDB, pageable);
            for (Content content : slice.getContent()) {
                total++;
                if (content.getType() == ContentType.SPORT) {
                    // SPORT는 TMDB 소스로 존재할 수 없는 조합. 방어적으로 건너뛴다.
                    log.warn("TMDB 소스인데 type=SPORT라 현지화를 건너뜁니다. contentId={}", content.getId());
                    continue;
                }
                if (localizeOne(content)) {
                    updated++;
                } else {
                    failed++;
                }
                sleepBetweenCalls();
            }
            pageable = pageable.next();
        } while (slice.hasNext());

        log.info("TMDB 콘텐츠 현지화 백필 완료. total={}, updated={}, failed={}", total, updated, failed);
        return new BackfillResult(total, updated, failed);
    }

    private boolean localizeOne(Content content) {
        try {
            String title;
            String overview;
            if (content.getType() == ContentType.MOVIE) {
                TmdbMovieDetail detail = tmdbApiClient.getMovieDetail(content.getExternalId());
                if (detail == null) {
                    throw new IllegalStateException("TMDB movie detail 응답이 비어 있습니다.");
                }
                title = detail.title();
                overview = detail.overview();
            } else {
                TmdbTvDetail detail = tmdbApiClient.getTvDetail(content.getExternalId());
                if (detail == null) {
                    throw new IllegalStateException("TMDB TV detail 응답이 비어 있습니다.");
                }
                title = detail.name();
                overview = detail.overview();
            }
            tmdbContentLocalizer.localize(content.getId(), title, overview);
            return true;
        } catch (Exception e) {
            log.warn("TMDB 콘텐츠 현지화 실패, 건너뜁니다. contentId={}, type={}, externalId={}",
                    content.getId(), content.getType(), content.getExternalId(), e);
            return false;
        }
    }

    private void sleepBetweenCalls() {
        try {
            Thread.sleep(CALL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record BackfillResult(int total, int updated, int failed) {
    }
}
