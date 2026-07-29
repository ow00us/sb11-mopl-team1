package com.mopl.content.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentSource;
import com.mopl.content.entity.ContentType;
import com.mopl.global.config.JpaConfig;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ContentRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    ContentRepository contentRepository;

    private Content.ContentBuilder movie() {
        return Content.builder()
                .type(ContentType.MOVIE)
                .title("The Matrix")
                .description("A hacker discovers reality is a simulation.");
    }

    @Test
    @DisplayName("저장한 콘텐츠를 ID로 조회하면 기본값과 함께 반환된다")
    void save_and_findById_success() {
        // given
        Content content = movie()
                .source(ContentSource.TMDB)
                .externalId("603")
                .thumbnailUrl("https://image.tmdb.org/matrix.jpg")
                .build();
        Content saved = entityManager.persistAndFlush(content);
        UUID contentId = saved.getId();
        entityManager.clear();

        // when
        Optional<Content> result = contentRepository.findById(contentId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getTitle()).isEqualTo("The Matrix");
        assertThat(result.get().getAverageRating()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.get().getReviewCount()).isEqualTo(0L);
        assertThat(result.get().getWatcherCount()).isEqualTo(0L);
        assertThat(result.get().getCreatedAt()).isNotNull();
        assertThat(result.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("externalId만 있고 source가 없으면 생성 시점에 BusinessException이 발생한다")
    void constructor_externalId_without_source_throws_business_exception() {
        assertThatThrownBy(() -> movie().externalId("603").build())
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("addTag는 대소문자·공백이 다른 입력을 정규화해서 저장한다")
    void addTag_normalizes_and_persists() {
        // given
        Content content = movie().build();
        content.addTag("  Action ");
        content.addTag("SF");

        // when
        Content saved = entityManager.persistAndFlush(content);
        entityManager.clear();
        Content found = contentRepository.findById(saved.getId()).orElseThrow();

        // then
        assertThat(found.getTags()).containsExactlyInAnyOrder("action", "sf");
    }

    @Test
    @DisplayName("정규화 후 동일해지는 태그를 중복 추가하면 거부된다")
    void addTag_duplicate_after_normalization_rejected() {
        // given
        Content content = movie().build();

        // when
        boolean first = content.addTag("Action");
        boolean second = content.addTag(" action ");

        // then
        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(content.getTags()).containsExactly("action");
    }

    @Test
    @DisplayName("getTags()로 얻은 컬렉션은 읽기 전용이라 직접 수정할 수 없다")
    void getTags_returns_unmodifiable_view() {
        // given
        Content content = movie().build();
        content.addTag("action");

        // when & then
        assertThatThrownBy(() -> content.getTags().add("우회"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("addTag에 null을 전달하면 BusinessException이 발생한다")
    void addTag_null_throws_business_exception() {
        // given
        Content content = movie().build();

        // when & then
        assertThatThrownBy(() -> content.addTag(null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @ParameterizedTest
    @DisplayName("addTag에 공백뿐인 문자열을 전달하면 BusinessException이 발생한다")
    @ValueSource(strings = {"", "   ", "\t\n"})
    void addTag_blank_after_normalization_throws_business_exception(String blankTag) {
        // given
        Content content = movie().build();

        // when & then
        assertThatThrownBy(() -> content.addTag(blankTag))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("동일한 (source, external_id) 조합을 저장하면 제약 위반이 발생한다")
    void duplicate_source_external_id_violates_constraint() {
        // given
        entityManager.persistAndFlush(movie().source(ContentSource.TMDB).externalId("603").build());
        Content duplicate = movie().source(ContentSource.TMDB).externalId("603").build();

        // when & then
        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @DisplayName("average_rating이 0.0~5.0 범위를 벗어나면 제약 위반이 발생한다")
    @ValueSource(strings = {"-0.1", "5.1"})
    void average_rating_out_of_range_violates_constraint(String invalidRating) {
        assertThatThrownBy(() -> insertRawContent("average_rating", invalidRating))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("review_count가 음수면 제약 위반이 발생한다")
    void review_count_negative_violates_constraint() {
        assertThatThrownBy(() -> insertRawContent("review_count", "-1"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("watcher_count가 음수면 제약 위반이 발생한다")
    void watcher_count_negative_violates_constraint() {
        assertThatThrownBy(() -> insertRawContent("watcher_count", "-1"))
                .isInstanceOf(RuntimeException.class);
    }

    private void insertRawContent(String columnName, String rawValue) {
        String sql = "INSERT INTO contents "
                + "(id, created_at, updated_at, type, title, description, average_rating, review_count, watcher_count) "
                + "VALUES (:id, now(), now(), 'MOVIE', 'title', 'description', "
                + valueOrDefault(columnName, "average_rating", rawValue, "0.0") + ", "
                + valueOrDefault(columnName, "review_count", rawValue, "0") + ", "
                + valueOrDefault(columnName, "watcher_count", rawValue, "0") + ")";
        entityManager.getEntityManager()
                .createNativeQuery(sql)
                .setParameter("id", UUID.randomUUID())
                .executeUpdate();
    }

    private String valueOrDefault(String targetColumn, String candidateColumn, String rawValue, String defaultValue) {
        return targetColumn.equals(candidateColumn) ? rawValue : defaultValue;
    }

    @Test
    @DisplayName("deleted_at이 채워진 콘텐츠는 기본 조회에서 제외된다")
    void deleted_content_excluded_from_default_queries() {
        // given
        Content saved = entityManager.persistAndFlush(movie().build());
        UUID contentId = saved.getId();
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE contents SET deleted_at = now() WHERE id = :id")
                .setParameter("id", contentId)
                .executeUpdate();
        entityManager.clear();

        // when
        Optional<Content> found = contentRepository.findById(contentId);
        List<Content> all = contentRepository.findAll();

        // then
        assertThat(found).isEmpty();
        assertThat(all).extracting(Content::getId).doesNotContain(contentId);
    }

    @Test
    @DisplayName("delete 호출 시 실제로는 논리 삭제(deleted_at 갱신)로 처리된다")
    void delete_performs_logical_delete() {
        // given
        Content saved = entityManager.persistAndFlush(movie().build());
        UUID contentId = saved.getId();

        // when
        contentRepository.deleteById(contentId);
        entityManager.flush();
        entityManager.clear();

        // then
        // 1) 기본 조회에서는 나오지 않음
        assertThat(contentRepository.findById(contentId)).isEmpty();

        // 2) 실제 행은 DB에 남아 있음 (네이티브 쿼리로 확인, @SQLRestriction 우회)
        Object[] row = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("SELECT id, deleted_at FROM contents WHERE id = :id")
                .setParameter("id", contentId)
                .getSingleResult();
        assertThat(row[0]).isEqualTo(contentId);

        // 3) deleted_at에 삭제 시각이 기록되어 있음
        assertThat(row[1]).isNotNull();
    }

    // ── 목록 조회 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tagsIn으로 조회하면 요청한 태그를 모두 가진 콘텐츠만 반환된다(AND 매칭)")
    void findByCreatedAtDesc_tagsIn_matchesAllRequestedTags() {
        Instant now = Instant.now();
        UUID both = insertContent("Both Tags", BigDecimal.ZERO, 0, now, "MOVIE");
        insertTag(both, "action");
        insertTag(both, "sf");
        UUID onlyOne = insertContent("Only One Tag", BigDecimal.ZERO, 0, now.minusSeconds(1), "MOVIE");
        insertTag(onlyOne, "action");

        List<Content> result = contentRepository.findByCreatedAtDesc(
                null, null, List.of("action", "sf"), 2, null, null, 10);
        long count = contentRepository.countByFilter(null, null, List.of("action", "sf"), 2);

        assertThat(result).extracting(Content::getId).containsExactly(both);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("typeEqual과 keywordLike 필터가 함께 적용된다")
    void findByCreatedAtDesc_appliesTypeAndKeywordFilters() {
        Instant now = Instant.now();
        UUID matched = insertContent("Matrix Reloaded", BigDecimal.ZERO, 0, now, "MOVIE");
        insertContent("Matrix Series", BigDecimal.ZERO, 0, now.minusSeconds(1), "TV_SERIES");
        insertContent("Unrelated Title", BigDecimal.ZERO, 0, now.minusSeconds(2), "MOVIE");

        List<Content> result = contentRepository.findByCreatedAtDesc(
                "MOVIE", "matrix", List.of(""), 0, null, null, 10);

        assertThat(result).extracting(Content::getId).containsExactly(matched);
    }

    @Test
    @DisplayName("keywordLike에 %가 포함되면 이스케이프된 값 기준으로 리터럴 매칭만 된다")
    void findByCreatedAtDesc_keywordWithPercent_matchesLiterally() {
        Instant now = Instant.now();
        UUID literalMatch = insertContent("50% off sale", BigDecimal.ZERO, 0, now, "MOVIE");
        insertContent("50X off sale", BigDecimal.ZERO, 0, now.minusSeconds(1), "MOVIE");

        List<Content> result = contentRepository.findByCreatedAtDesc(
                null, "50\\%", List.of(""), 0, null, null, 10);

        assertThat(result).extracting(Content::getId).containsExactly(literalMatch);
    }

    @Test
    @DisplayName("논리 삭제된 콘텐츠는 목록/카운트 네이티브 쿼리에서도 제외된다")
    void findByCreatedAtDesc_excludesDeletedContent() {
        Instant now = Instant.now();
        UUID active = insertContent("Active", BigDecimal.ZERO, 0, now, "MOVIE");
        UUID deleted = insertContent("Deleted", BigDecimal.ZERO, 0, now.minusSeconds(1), "MOVIE");
        markDeleted(deleted);

        List<Content> result = contentRepository.findByCreatedAtDesc(
                null, null, List.of(""), 0, null, null, 10);
        long count = contentRepository.countByFilter(null, null, List.of(""), 0);

        assertThat(result).extracting(Content::getId).containsExactly(active);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("createdAt DESC 정렬로 커서 페이지네이션 시 2페이지에 걸쳐 전부 조회된다")
    void findByCreatedAtDesc_pagination_acrossTwoPages() {
        Instant base = Instant.now();
        UUID id1 = insertContent("C1", BigDecimal.ZERO, 0, base.minusSeconds(3), "MOVIE");
        UUID id2 = insertContent("C2", BigDecimal.ZERO, 0, base.minusSeconds(2), "MOVIE");
        UUID id3 = insertContent("C3", BigDecimal.ZERO, 0, base.minusSeconds(1), "MOVIE");

        List<Content> firstPage = contentRepository.findByCreatedAtDesc(
                null, null, List.of(""), 0, null, null, 2);
        assertThat(firstPage).extracting(Content::getId).containsExactly(id3, id2);

        Content last = firstPage.get(firstPage.size() - 1);
        List<Content> secondPage = contentRepository.findByCreatedAtDesc(
                null, null, List.of(""), 0, last.getCreatedAt(), last.getId().toString(), 2);
        assertThat(secondPage).extracting(Content::getId).containsExactly(id1);
    }

    @Test
    @DisplayName("watcherCount ASC 정렬로 커서 페이지네이션이 정상 동작한다")
    void findByWatcherCountAsc_pagination_acrossTwoPages() {
        Instant now = Instant.now();
        UUID id1 = insertContent("W1", BigDecimal.ZERO, 10, now, "MOVIE");
        UUID id2 = insertContent("W2", BigDecimal.ZERO, 20, now, "MOVIE");
        UUID id3 = insertContent("W3", BigDecimal.ZERO, 30, now, "MOVIE");

        List<Content> firstPage = contentRepository.findByWatcherCountAsc(
                null, null, List.of(""), 0, null, null, 2);
        assertThat(firstPage).extracting(Content::getId).containsExactly(id1, id2);

        Content last = firstPage.get(firstPage.size() - 1);
        List<Content> secondPage = contentRepository.findByWatcherCountAsc(
                null, null, List.of(""), 0, last.getWatcherCount(), last.getId().toString(), 2);
        assertThat(secondPage).extracting(Content::getId).containsExactly(id3);
    }

    @Test
    @DisplayName("averageRating DESC 정렬로 커서 페이지네이션이 정상 동작한다")
    void findByAverageRatingDesc_pagination_acrossTwoPages() {
        Instant now = Instant.now();
        UUID id1 = insertContent("R1", new BigDecimal("1.0"), 0, now, "MOVIE");
        UUID id2 = insertContent("R2", new BigDecimal("3.0"), 0, now, "MOVIE");
        UUID id3 = insertContent("R3", new BigDecimal("5.0"), 0, now, "MOVIE");

        List<Content> firstPage = contentRepository.findByAverageRatingDesc(
                null, null, List.of(""), 0, null, null, 2);
        assertThat(firstPage).extracting(Content::getId).containsExactly(id3, id2);

        Content last = firstPage.get(firstPage.size() - 1);
        List<Content> secondPage = contentRepository.findByAverageRatingDesc(
                null, null, List.of(""), 0, last.getAverageRating(), last.getId().toString(), 2);
        assertThat(secondPage).extracting(Content::getId).containsExactly(id1);
    }

    @Test
    @DisplayName("watcherCount가 같으면 reviewCount가 많은 콘텐츠가 먼저 나온다(2차 정렬)")
    void findByWatcherCountDesc_tieBreaksByReviewCount() {
        Instant now = Instant.now();
        UUID lowReview = insertContent("Low Review", BigDecimal.ZERO, 10, 1, now, "MOVIE");
        UUID highReview = insertContent("High Review", BigDecimal.ZERO, 10, 5, now.minusSeconds(1), "MOVIE");

        List<Content> result = contentRepository.findByWatcherCountDesc(
                null, null, List.of(""), 0, null, null, null, 10);

        assertThat(result).extracting(Content::getId).containsExactly(highReview, lowReview);
    }

    @Test
    @DisplayName("watcherCount DESC 정렬은 (watcherCount, reviewCount, id) 복합 커서로 2페이지에 걸쳐 정상 동작한다")
    void findByWatcherCountDesc_pagination_acrossTwoPages_withReviewCountTiebreak() {
        Instant now = Instant.now();
        UUID id1 = insertContent("W1", BigDecimal.ZERO, 10, 1, now, "MOVIE");
        UUID id2 = insertContent("W2", BigDecimal.ZERO, 10, 5, now, "MOVIE");
        UUID id3 = insertContent("W3", BigDecimal.ZERO, 20, 0, now, "MOVIE");

        List<Content> firstPage = contentRepository.findByWatcherCountDesc(
                null, null, List.of(""), 0, null, null, null, 2);
        assertThat(firstPage).extracting(Content::getId).containsExactly(id3, id2);

        Content last = firstPage.get(firstPage.size() - 1);
        List<Content> secondPage = contentRepository.findByWatcherCountDesc(
                null, null, List.of(""), 0, last.getWatcherCount(), last.getReviewCount(),
                last.getId().toString(), 2);
        assertThat(secondPage).extracting(Content::getId).containsExactly(id1);
    }

    private UUID insertContent(
            String title, BigDecimal averageRating, long watcherCount, Instant createdAt, String type) {
        return insertContent(title, averageRating, watcherCount, 0, createdAt, type);
    }

    private UUID insertContent(
            String title, BigDecimal averageRating, long watcherCount, long reviewCount,
            Instant createdAt, String type) {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO contents "
                        + "(id, created_at, updated_at, type, title, description, average_rating, review_count, watcher_count) "
                        + "VALUES (:id, :createdAt, :createdAt, :type, :title, 'description', :averageRating, :reviewCount, :watcherCount)")
                .setParameter("id", id)
                .setParameter("createdAt", createdAt)
                .setParameter("type", type)
                .setParameter("title", title)
                .setParameter("averageRating", averageRating)
                .setParameter("reviewCount", reviewCount)
                .setParameter("watcherCount", watcherCount)
                .executeUpdate();
        return id;
    }

    private void insertTag(UUID contentId, String tag) {
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO content_tags (content_id, tag) VALUES (:contentId, :tag)")
                .setParameter("contentId", contentId)
                .setParameter("tag", tag)
                .executeUpdate();
    }

    private void markDeleted(UUID contentId) {
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE contents SET deleted_at = now() WHERE id = :id")
                .setParameter("id", contentId)
                .executeUpdate();
    }
}
