package com.mopl.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

class ContentSearchExecutorTest {

    private final ElasticsearchOperations elasticsearchOperations = mock(ElasticsearchOperations.class);
    private final ContentSearchQueryFactory queryFactory = mock(ContentSearchQueryFactory.class);
    private final ContentSearchExecutor executor = new ContentSearchExecutor(elasticsearchOperations, queryFactory);

    private final NativeQuery query = NativeQuery.builder().build();

    @Test
    @DisplayName("findByCreatedAtAsc는 queryFactory가 만든 쿼리로 검색해 ContentDocument 리스트를 순서대로 반환한다")
    void findByCreatedAtAsc_searchesAndExtractsDocumentsInOrder() {
        Instant cursorTime = Instant.now();
        when(queryFactory.createByCreatedAtAsc("movie", "키워드", List.of("action"), cursorTime, "id-1", 10))
                .thenReturn(query);
        List<ContentDocument> docs = List.of(document("1"), document("2"));
        mockSearchResult(docs);

        List<ContentDocument> result = executor.findByCreatedAtAsc(
                "movie", "키워드", List.of("action"), cursorTime, "id-1", 10);

        assertThat(result).containsExactlyElementsOf(docs);
    }

    @Test
    @DisplayName("findByCreatedAtDesc는 queryFactory가 만든 쿼리로 검색해 ContentDocument 리스트를 순서대로 반환한다")
    void findByCreatedAtDesc_searchesAndExtractsDocumentsInOrder() {
        Instant cursorTime = Instant.now();
        when(queryFactory.createByCreatedAtDesc("movie", "키워드", List.of("action"), cursorTime, "id-1", 10))
                .thenReturn(query);
        List<ContentDocument> docs = List.of(document("1"), document("2"));
        mockSearchResult(docs);

        List<ContentDocument> result = executor.findByCreatedAtDesc(
                "movie", "키워드", List.of("action"), cursorTime, "id-1", 10);

        assertThat(result).containsExactlyElementsOf(docs);
    }

    @Test
    @DisplayName("findByWatcherCountAsc는 queryFactory가 만든 쿼리로 검색해 ContentDocument 리스트를 순서대로 반환한다")
    void findByWatcherCountAsc_searchesAndExtractsDocumentsInOrder() {
        when(queryFactory.createByWatcherCountAsc("movie", "키워드", List.of("action"), 5L, "id-1", 10))
                .thenReturn(query);
        List<ContentDocument> docs = List.of(document("1"), document("2"));
        mockSearchResult(docs);

        List<ContentDocument> result = executor.findByWatcherCountAsc(
                "movie", "키워드", List.of("action"), 5L, "id-1", 10);

        assertThat(result).containsExactlyElementsOf(docs);
    }

    @Test
    @DisplayName("findByWatcherCountDesc는 queryFactory가 만든 쿼리로 검색해 ContentDocument 리스트를 순서대로 반환한다")
    void findByWatcherCountDesc_searchesAndExtractsDocumentsInOrder() {
        when(queryFactory.createByWatcherCountDesc("movie", "키워드", List.of("action"), 5L, 3L, "id-1", 10))
                .thenReturn(query);
        List<ContentDocument> docs = List.of(document("1"), document("2"));
        mockSearchResult(docs);

        List<ContentDocument> result = executor.findByWatcherCountDesc(
                "movie", "키워드", List.of("action"), 5L, 3L, "id-1", 10);

        assertThat(result).containsExactlyElementsOf(docs);
    }

    @Test
    @DisplayName("findByAverageRatingAsc는 queryFactory가 만든 쿼리로 검색해 ContentDocument 리스트를 순서대로 반환한다")
    void findByAverageRatingAsc_searchesAndExtractsDocumentsInOrder() {
        BigDecimal cursorRating = new BigDecimal("4.5");
        when(queryFactory.createByAverageRatingAsc("movie", "키워드", List.of("action"), cursorRating, "id-1", 10))
                .thenReturn(query);
        List<ContentDocument> docs = List.of(document("1"), document("2"));
        mockSearchResult(docs);

        List<ContentDocument> result = executor.findByAverageRatingAsc(
                "movie", "키워드", List.of("action"), cursorRating, "id-1", 10);

        assertThat(result).containsExactlyElementsOf(docs);
    }

    @Test
    @DisplayName("findByAverageRatingDesc는 queryFactory가 만든 쿼리로 검색해 ContentDocument 리스트를 순서대로 반환한다")
    void findByAverageRatingDesc_searchesAndExtractsDocumentsInOrder() {
        BigDecimal cursorRating = new BigDecimal("4.5");
        when(queryFactory.createByAverageRatingDesc("movie", "키워드", List.of("action"), cursorRating, "id-1", 10))
                .thenReturn(query);
        List<ContentDocument> docs = List.of(document("1"), document("2"));
        mockSearchResult(docs);

        List<ContentDocument> result = executor.findByAverageRatingDesc(
                "movie", "키워드", List.of("action"), cursorRating, "id-1", 10);

        assertThat(result).containsExactlyElementsOf(docs);
    }

    @Test
    @DisplayName("countByFilter는 queryFactory.createCountQuery()로 만든 쿼리로 count를 호출한다")
    void countByFilter_callsCountWithFactoryQuery() {
        when(queryFactory.createCountQuery("movie", "키워드", List.of("action"))).thenReturn(query);
        when(elasticsearchOperations.count(query, ContentDocument.class)).thenReturn(7L);

        long result = executor.countByFilter("movie", "키워드", List.of("action"));

        assertThat(result).isEqualTo(7L);
        verify(elasticsearchOperations).count(query, ContentDocument.class);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void mockSearchResult(List<ContentDocument> docs) {
        @SuppressWarnings("unchecked")
        SearchHits<ContentDocument> hits = mock(SearchHits.class);
        List<SearchHit<ContentDocument>> searchHits = docs.stream().map(this::searchHit).toList();
        when(hits.getSearchHits()).thenReturn(searchHits);
        when(elasticsearchOperations.search(query, ContentDocument.class)).thenReturn(hits);
    }

    @SuppressWarnings("unchecked")
    private SearchHit<ContentDocument> searchHit(ContentDocument document) {
        SearchHit<ContentDocument> hit = mock(SearchHit.class);
        when(hit.getContent()).thenReturn(document);
        return hit;
    }

    private ContentDocument document(String id) {
        return ContentDocument.builder().id(id).contentId(id).build();
    }
}
