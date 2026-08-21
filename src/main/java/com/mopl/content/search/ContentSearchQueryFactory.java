package com.mopl.content.search;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.mopl.content.entity.ContentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.stereotype.Component;

/**
 * 콘텐츠 목록 조회를 위한 Elasticsearch 쿼리를 만든다.
 * ContentRepository의 네이티브 쿼리 메서드들과 같은 필터·정렬·커서 계약을 ES 쿼리로 재현한다.
 */
@Component
public class ContentSearchQueryFactory {

    private static final String FIELD_TITLE = "title";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_TAGS = "tags";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_WATCHER_COUNT = "watcherCount";
    private static final String FIELD_REVIEW_COUNT = "reviewCount";
    private static final String FIELD_AVERAGE_RATING = "averageRating";

    // _id는 ES 7+에서 fielddata가 기본 비활성화라 정렬/search_after의 tie-breaker로 쓸 수 없다.
    // 대신 ContentDocument.contentId(keyword)를 별도로 두고 이 필드로 정렬한다.
    private static final String FIELD_CONTENT_ID = "contentId";

    // ── createdAt 정렬 ──────────────────────────────────────────────────────

    public NativeQuery createByCreatedAtAsc(
            String typeEqual, String keywordLike, List<String> tagsIn,
            Instant cursorTime, String idAfter, int limit) {
        List<SortOptions> sort = List.of(
                fieldSort(FIELD_CREATED_AT, SortOrder.Asc),
                fieldSort(FIELD_CONTENT_ID, SortOrder.Asc));
        List<Object> searchAfter = cursorTime != null
                ? List.of(createdAtSortValue(cursorTime), idAfter)
                : null;
        return build(typeEqual, keywordLike, tagsIn, sort, searchAfter, limit);
    }

    public NativeQuery createByCreatedAtDesc(
            String typeEqual, String keywordLike, List<String> tagsIn,
            Instant cursorTime, String idAfter, int limit) {
        List<SortOptions> sort = List.of(
                fieldSort(FIELD_CREATED_AT, SortOrder.Desc),
                fieldSort(FIELD_CONTENT_ID, SortOrder.Asc));
        List<Object> searchAfter = cursorTime != null
                ? List.of(createdAtSortValue(cursorTime), idAfter)
                : null;
        return build(typeEqual, keywordLike, tagsIn, sort, searchAfter, limit);
    }

    // ── watcherCount 정렬 ───────────────────────────────────────────────────
    // ContentDocument.watcherCount는 WatcherCountRefreshService가 60초 주기로 채우는 배치 값이다.
    // 실시간 집계 대신 이 필드 기준으로 정렬해 필터·정렬·페이지네이션을 ES 쿼리 하나로 처리한다.

    public NativeQuery createByWatcherCountAsc(
            String typeEqual, String keywordLike, List<String> tagsIn,
            Long cursorCount, String idAfter, int limit) {
        List<SortOptions> sort = List.of(
                fieldSort(FIELD_WATCHER_COUNT, SortOrder.Asc),
                fieldSort(FIELD_CONTENT_ID, SortOrder.Asc));
        List<Object> searchAfter = cursorCount != null
                ? List.of(cursorCount, idAfter)
                : null;
        return build(typeEqual, keywordLike, tagsIn, sort, searchAfter, limit);
    }

    public NativeQuery createByWatcherCountDesc(
            String typeEqual, String keywordLike, List<String> tagsIn,
            Long cursorWatcherCount, Long cursorReviewCount, String idAfter, int limit) {
        List<SortOptions> sort = List.of(
                fieldSort(FIELD_WATCHER_COUNT, SortOrder.Desc),
                fieldSort(FIELD_REVIEW_COUNT, SortOrder.Desc),
                fieldSort(FIELD_CONTENT_ID, SortOrder.Asc));
        List<Object> searchAfter = cursorWatcherCount != null
                ? List.of(cursorWatcherCount, cursorReviewCount, idAfter)
                : null;
        return build(typeEqual, keywordLike, tagsIn, sort, searchAfter, limit);
    }

    // ── averageRating 정렬 ──────────────────────────────────────────────────

    public NativeQuery createByAverageRatingAsc(
            String typeEqual, String keywordLike, List<String> tagsIn,
            BigDecimal cursorRating, String idAfter, int limit) {
        List<SortOptions> sort = List.of(
                fieldSort(FIELD_AVERAGE_RATING, SortOrder.Asc),
                fieldSort(FIELD_CONTENT_ID, SortOrder.Asc));
        List<Object> searchAfter = cursorRating != null
                ? List.of(cursorRating.doubleValue(), idAfter)
                : null;
        return build(typeEqual, keywordLike, tagsIn, sort, searchAfter, limit);
    }

    public NativeQuery createByAverageRatingDesc(
            String typeEqual, String keywordLike, List<String> tagsIn,
            BigDecimal cursorRating, String idAfter, int limit) {
        List<SortOptions> sort = List.of(
                fieldSort(FIELD_AVERAGE_RATING, SortOrder.Desc),
                fieldSort(FIELD_CONTENT_ID, SortOrder.Asc));
        List<Object> searchAfter = cursorRating != null
                ? List.of(cursorRating.doubleValue(), idAfter)
                : null;
        return build(typeEqual, keywordLike, tagsIn, sort, searchAfter, limit);
    }

    // ── 공통 ────────────────────────────────────────────────────────────────

    private NativeQuery build(
            String typeEqual, String keywordLike, List<String> tagsIn,
            List<SortOptions> sort, List<Object> searchAfter, int limit) {

        BoolQuery.Builder bool = new BoolQuery.Builder();

        if (keywordLike != null && !keywordLike.isBlank()) {
            bool.must(m -> m.multiMatch(mm -> mm
                    .fields(FIELD_TITLE, FIELD_DESCRIPTION)
                    .query(keywordLike)));
        }

        List<Query> filters = new ArrayList<>();
        if (typeEqual != null) {
            filters.add(termQuery(FIELD_TYPE, ContentType.fromApiValue(typeEqual).name()));
        }
        if (tagsIn != null) {
            // 태그마다 term 필터를 하나씩 추가한다. bool.filter 절에 여러 term이 있으면
            // 전부 만족해야 매치되므로(AND) 기존 Postgres HAVING COUNT = tagCount와 동치가 된다.
            for (String tag : tagsIn) {
                filters.add(termQuery(FIELD_TAGS, tag));
            }
        }
        if (!filters.isEmpty()) {
            bool.filter(filters);
        }

        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(Query.of(q -> q.bool(bool.build())))
                .withSort(sort)
                .withMaxResults(limit);

        if (searchAfter != null) {
            builder.withSearchAfter(searchAfter);
        }

        return builder.build();
    }

    private SortOptions fieldSort(String field, SortOrder order) {
        return SortOptions.of(s -> s.field(f -> f.field(field).order(order)));
    }

    private Query termQuery(String field, String value) {
        return Query.of(q -> q.term(t -> t.field(field).value(value)));
    }

    // ContentDocumentMapper가 createdAt을 ZoneOffset.UTC 기준으로 색인하므로
    // (zone 없는 문자열은 ES가 UTC로 해석해 doc value를 만든다) 커서 값도 Instant 그대로 쓰면 된다.
    private long createdAtSortValue(Instant instant) {
        return instant.toEpochMilli();
    }
}
