package com.mopl.content.search;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Component;

/**
 * ContentSearchQueryFactory가 만든 쿼리를 실행해 ContentDocument 결과를 돌려준다.
 * ContentRepository의 대응 메서드들과 같은 모양이지만 몇 가지가 다르다:
 *
 * - tagCount 파라미터가 없다 — ES는 태그별 term 필터를 AND로 처리하므로 태그 리스트만 넘기면 된다.
 * - now(Instant) 파라미터가 없다 — watcherCount를 ES 필드 기준으로 통일했으므로
 *   실시간 서브쿼리용 현재 시각이 필요 없다.
 * - typeEqual은 ContentRepository와 의미가 다르다. ContentRepository의 typeEqual은
 *   이미 ContentType enum name(예: "MOVIE")으로 변환된 값을 받지만, 여기서는
 *   ContentSearchQueryFactory가 내부에서 ContentType.fromApiValue(typeEqual)로
 *   직접 변환하므로 API 원본 camelCase 값(예: "movie")을 그대로 넘겨야 한다. 이미 변환된
 *   enum name을 넘기면 fromApiValue()가 못 찾아 예외가 던져진다.
 *
 * hasNext 판단을 위한 limit+1 조회는 호출하는 쪽 책임이며, 여기서는 넘겨받은 limit을 그대로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class ContentSearchExecutor {

    private final ElasticsearchOperations elasticsearchOperations;
    private final ContentSearchQueryFactory queryFactory;

    public List<ContentDocument> findByCreatedAtAsc(
            String typeEqual, String keywordLike, List<String> tags,
            Long cursorEpochMicros, String idAfter, int limit) {
        return search(queryFactory.createByCreatedAtAsc(
                typeEqual, keywordLike, tags, cursorEpochMicros, idAfter, limit));
    }

    public List<ContentDocument> findByCreatedAtDesc(
            String typeEqual, String keywordLike, List<String> tags,
            Long cursorEpochMicros, String idAfter, int limit) {
        return search(queryFactory.createByCreatedAtDesc(
                typeEqual, keywordLike, tags, cursorEpochMicros, idAfter, limit));
    }

    public List<ContentDocument> findByWatcherCountAsc(
            String typeEqual, String keywordLike, List<String> tags,
            Long cursorCount, String idAfter, int limit) {
        return search(queryFactory.createByWatcherCountAsc(typeEqual, keywordLike, tags, cursorCount, idAfter, limit));
    }

    public List<ContentDocument> findByWatcherCountDesc(
            String typeEqual, String keywordLike, List<String> tags,
            Long cursorWatcherCount, Long cursorReviewCount, String idAfter, int limit) {
        return search(queryFactory.createByWatcherCountDesc(
                typeEqual, keywordLike, tags, cursorWatcherCount, cursorReviewCount, idAfter, limit));
    }

    public List<ContentDocument> findByAverageRatingAsc(
            String typeEqual, String keywordLike, List<String> tags,
            BigDecimal cursorRating, String idAfter, int limit) {
        return search(queryFactory.createByAverageRatingAsc(typeEqual, keywordLike, tags, cursorRating, idAfter, limit));
    }

    public List<ContentDocument> findByAverageRatingDesc(
            String typeEqual, String keywordLike, List<String> tags,
            BigDecimal cursorRating, String idAfter, int limit) {
        return search(queryFactory.createByAverageRatingDesc(typeEqual, keywordLike, tags, cursorRating, idAfter, limit));
    }

    public long countByFilter(String typeEqual, String keywordLike, List<String> tags) {
        NativeQuery query = queryFactory.createCountQuery(typeEqual, keywordLike, tags);
        return elasticsearchOperations.count(query, ContentDocument.class);
    }

    private List<ContentDocument> search(NativeQuery query) {
        SearchHits<ContentDocument> hits = elasticsearchOperations.search(query, ContentDocument.class);
        return hits.getSearchHits().stream().map(SearchHit::getContent).toList();
    }
}
