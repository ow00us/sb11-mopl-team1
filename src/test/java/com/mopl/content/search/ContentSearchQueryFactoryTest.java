package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MultiMatchQuery;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

class ContentSearchQueryFactoryTest {

    private final ContentSearchQueryFactory factory = new ContentSearchQueryFactory();

    // ── 필터 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("조건이 없으면 must/filter가 모두 비어있다(match_all과 동치)")
    void noConditions_mustAndFilterAreEmpty() {
        NativeQuery query = factory.createByCreatedAtDesc(null, null, List.of(), null, null, 10);

        BoolQuery bool = query.getQuery().bool();
        assertThat(bool.must()).isEmpty();
        assertThat(bool.filter()).isEmpty();
    }

    @Test
    @DisplayName("keywordLike만 있으면 must에 title/description 대상 multi_match가 하나 들어간다")
    void keywordLikeOnly_addsMultiMatchToMust() {
        NativeQuery query = factory.createByCreatedAtDesc(null, "마라톤", List.of(), null, null, 10);

        BoolQuery bool = query.getQuery().bool();
        assertThat(bool.must()).hasSize(1);
        MultiMatchQuery multiMatch = bool.must().get(0).multiMatch();
        assertThat(multiMatch.fields()).containsExactlyInAnyOrder("title", "description");
        assertThat(multiMatch.query()).isEqualTo("마라톤");
        assertThat(bool.filter()).isEmpty();
    }

    @Test
    @DisplayName("typeEqual만 있으면 filter에 type term이 들어가고 enum name으로 변환된다")
    void typeEqualOnly_addsTypeTermToFilter() {
        NativeQuery query = factory.createByCreatedAtDesc("tvSeries", null, List.of(), null, null, 10);

        BoolQuery bool = query.getQuery().bool();
        assertThat(bool.must()).isEmpty();
        assertThat(bool.filter()).hasSize(1);
        assertThat(bool.filter().get(0).term().field()).isEqualTo("type");
        assertThat(bool.filter().get(0).term().value().stringValue()).isEqualTo("TV_SERIES");
    }

    @Test
    @DisplayName("tagsIn이 2개 이상이면 filter에 태그 개수만큼 term이 들어가고 각각 값이 맞다(AND)")
    void multipleTags_addsOneTermPerTagToFilter() {
        NativeQuery query = factory.createByCreatedAtDesc(null, null, List.of("action", "sf"), null, null, 10);

        BoolQuery bool = query.getQuery().bool();
        assertThat(bool.filter()).hasSize(2);
        assertThat(bool.filter()).allSatisfy(q -> assertThat(q.term().field()).isEqualTo("tags"));
        assertThat(bool.filter().stream().map(q -> q.term().value().stringValue()))
                .containsExactlyInAnyOrder("action", "sf");
    }

    @Test
    @DisplayName("keywordLike/typeEqual/tagsIn이 모두 있으면 must에 multi_match, filter에 type+태그가 함께 들어간다")
    void keywordTypeAndTagsCombined() {
        NativeQuery query = factory.createByCreatedAtDesc("movie", "마라톤", List.of("action"), null, null, 10);

        BoolQuery bool = query.getQuery().bool();
        assertThat(bool.must()).hasSize(1);
        assertThat(bool.filter()).hasSize(2);
        assertThat(bool.filter().stream().map(q -> q.term().field()))
                .containsExactlyInAnyOrder("type", "tags");
    }

    @Test
    @DisplayName("잘못된 typeEqual이면 ContentType.fromApiValue()의 BusinessException(INVALID_INPUT)이 그대로 전파된다")
    void invalidTypeEqual_propagatesBusinessException() {
        assertThatThrownBy(() -> factory.createByCreatedAtDesc("invalid", null, List.of(), null, null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ── 정렬 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createByCreatedAtAsc는 createdAt asc, contentId asc 순으로 정렬한다")
    void createByCreatedAtAsc_sortsByCreatedAtAscThenContentId() {
        NativeQuery query = factory.createByCreatedAtAsc(null, null, List.of(), null, null, 10);

        assertSort(query, new FieldOrder("createdAt", SortOrder.Asc), new FieldOrder("contentId", SortOrder.Asc));
    }

    @Test
    @DisplayName("createByCreatedAtDesc는 createdAt desc, contentId asc 순으로 정렬한다")
    void createByCreatedAtDesc_sortsByCreatedAtDescThenContentId() {
        NativeQuery query = factory.createByCreatedAtDesc(null, null, List.of(), null, null, 10);

        assertSort(query, new FieldOrder("createdAt", SortOrder.Desc), new FieldOrder("contentId", SortOrder.Asc));
    }

    @Test
    @DisplayName("createByWatcherCountAsc는 watcherCount asc, contentId asc 순으로 정렬한다")
    void createByWatcherCountAsc_sortsByWatcherCountAscThenContentId() {
        NativeQuery query = factory.createByWatcherCountAsc(null, null, List.of(), null, null, 10);

        assertSort(query, new FieldOrder("watcherCount", SortOrder.Asc), new FieldOrder("contentId", SortOrder.Asc));
    }

    @Test
    @DisplayName("createByWatcherCountDesc는 watcherCount desc, reviewCount desc(2차), contentId asc 순으로 정렬한다")
    void createByWatcherCountDesc_includesReviewCountAsSecondarySort() {
        NativeQuery query = factory.createByWatcherCountDesc(null, null, List.of(), null, null, null, 10);

        assertSort(query,
                new FieldOrder("watcherCount", SortOrder.Desc),
                new FieldOrder("reviewCount", SortOrder.Desc),
                new FieldOrder("contentId", SortOrder.Asc));
    }

    @Test
    @DisplayName("createByAverageRatingAsc는 averageRating asc, contentId asc 순으로 정렬한다")
    void createByAverageRatingAsc_sortsByAverageRatingAscThenContentId() {
        NativeQuery query = factory.createByAverageRatingAsc(null, null, List.of(), null, null, 10);

        assertSort(query, new FieldOrder("averageRating", SortOrder.Asc), new FieldOrder("contentId", SortOrder.Asc));
    }

    @Test
    @DisplayName("createByAverageRatingDesc는 averageRating desc, contentId asc 순으로 정렬한다")
    void createByAverageRatingDesc_sortsByAverageRatingDescThenContentId() {
        NativeQuery query = factory.createByAverageRatingDesc(null, null, List.of(), null, null, 10);

        assertSort(query, new FieldOrder("averageRating", SortOrder.Desc), new FieldOrder("contentId", SortOrder.Asc));
    }

    // ── 커서(search_after) ─────────────────────────────────────────────────

    @Test
    @DisplayName("커서가 없으면 searchAfter는 null이다")
    void noCursor_searchAfterIsNull() {
        NativeQuery query = factory.createByCreatedAtDesc(null, null, List.of(), null, null, 10);

        assertThat(query.getSearchAfter()).isNull();
    }

    @Test
    @DisplayName("createdAt 커서는 Instant.toEpochMilli() 값이 idAfter와 함께 그대로 searchAfter에 들어간다")
    void createdAtCursor_usesEpochMilliDirectly() {
        Instant cursorTime = Instant.parse("2026-08-10T04:50:16.026Z");

        NativeQuery query = factory.createByCreatedAtDesc(null, null, List.of(), cursorTime, "id-1", 10);

        assertThat(query.getSearchAfter()).containsExactly(cursorTime.toEpochMilli(), "id-1");
    }

    @Test
    @DisplayName("watcherCount asc 커서는 (cursorCount, idAfter) 순으로 들어간다")
    void watcherCountAscCursor_ordersCountThenIdAfter() {
        NativeQuery query = factory.createByWatcherCountAsc(null, null, List.of(), 5L, "id-1", 10);

        assertThat(query.getSearchAfter()).containsExactly(5L, "id-1");
    }

    @Test
    @DisplayName("watcherCount desc 커서는 (cursorWatcherCount, cursorReviewCount, idAfter) 순으로 들어간다")
    void watcherCountDescCursor_ordersWatcherCountThenReviewCountThenIdAfter() {
        NativeQuery query = factory.createByWatcherCountDesc(null, null, List.of(), 5L, 3L, "id-1", 10);

        assertThat(query.getSearchAfter()).containsExactly(5L, 3L, "id-1");
    }

    @Test
    @DisplayName("averageRating 커서는 (cursorRating.doubleValue(), idAfter) 순으로 들어간다")
    void averageRatingCursor_ordersRatingThenIdAfter() {
        NativeQuery query = factory.createByAverageRatingAsc(null, null, List.of(), new BigDecimal("4.5"), "id-1", 10);

        assertThat(query.getSearchAfter()).containsExactly(4.5, "id-1");
    }

    // ── countByFilter ──────────────────────────────────────────────────────

    @Test
    @DisplayName("createCountQuery()는 필터만 있고 정렬·limit·searchAfter는 없다")
    void createCountQuery_hasOnlyFilterNoSortNoLimitNoSearchAfter() {
        NativeQuery query = factory.createCountQuery("movie", "마라톤", List.of("action"));

        BoolQuery bool = query.getQuery().bool();
        assertThat(bool.must()).hasSize(1);
        assertThat(bool.filter()).hasSize(2);
        assertThat(query.getSortOptions()).isEmpty();
        assertThat(query.getMaxResults()).isNull();
        assertThat(query.getSearchAfter()).isNull();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private record FieldOrder(String field, SortOrder order) {}

    private void assertSort(NativeQuery query, FieldOrder... expected) {
        List<FieldOrder> actual = query.getSortOptions().stream()
                .map(SortOptions::field)
                .map(f -> new FieldOrder(f.field(), f.order()))
                .toList();
        assertThat(actual).containsExactly(expected);
    }
}
