package com.mopl.content.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.content.dto.ContentDto;
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
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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
        assertThat(found.getTags()).containsExactlyInAnyOrder("Action", "SF");
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
        // 나중에 추가한 " action "이 소문자라서 표기(display)도 우연히 "action"으로 남는다.
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
    @DisplayName("watcherCount ASC 정렬로 커서 페이지네이션이 정상 동작한다 (watching_session_snapshots 실시간 집계 기준)")
    void findByWatcherCountAsc_pagination_acrossTwoPages() {
        Instant now = Instant.now();
        UUID id1 = insertContent("W1", BigDecimal.ZERO, 0, now, "MOVIE");
        UUID id2 = insertContent("W2", BigDecimal.ZERO, 0, now, "MOVIE");
        UUID id3 = insertContent("W3", BigDecimal.ZERO, 0, now, "MOVIE");
        insertWatchingSessions(id1, 1);
        insertWatchingSessions(id2, 2);
        insertWatchingSessions(id3, 3);

        List<Content> firstPage = contentRepository.findByWatcherCountAsc(
                null, null, List.of(""), 0, null, null, now, 2);
        assertThat(firstPage).extracting(Content::getId).containsExactly(id1, id2);

        Content last = firstPage.get(firstPage.size() - 1);
        List<Content> secondPage = contentRepository.findByWatcherCountAsc(
                null, null, List.of(""), 0, 2L, last.getId().toString(), now, 2);
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
    @DisplayName("watcherCount가 같으면 reviewCount가 많은 콘텐츠가 먼저 나온다(2차 정렬, watching_session_snapshots 실시간 집계 기준)")
    void findByWatcherCountDesc_tieBreaksByReviewCount() {
        Instant now = Instant.now();
        UUID lowReview = insertContent("Low Review", BigDecimal.ZERO, 0, 1, now, "MOVIE");
        UUID highReview = insertContent("High Review", BigDecimal.ZERO, 0, 5, now.minusSeconds(1), "MOVIE");
        insertWatchingSessions(lowReview, 2);
        insertWatchingSessions(highReview, 2);

        List<Content> result = contentRepository.findByWatcherCountDesc(
                null, null, List.of(""), 0, null, null, null, now, 10);

        assertThat(result).extracting(Content::getId).containsExactly(highReview, lowReview);
    }

    @Test
    @DisplayName("watcherCount DESC 정렬은 (watcherCount, reviewCount, id) 복합 커서로 2페이지에 걸쳐 정상 동작한다 (watching_session_snapshots 실시간 집계 기준)")
    void findByWatcherCountDesc_pagination_acrossTwoPages_withReviewCountTiebreak() {
        Instant now = Instant.now();
        UUID id1 = insertContent("W1", BigDecimal.ZERO, 0, 1, now, "MOVIE");
        UUID id2 = insertContent("W2", BigDecimal.ZERO, 0, 5, now, "MOVIE");
        UUID id3 = insertContent("W3", BigDecimal.ZERO, 0, 0, now, "MOVIE");
        insertWatchingSessions(id1, 1);
        insertWatchingSessions(id2, 1);
        insertWatchingSessions(id3, 2);

        List<Content> firstPage = contentRepository.findByWatcherCountDesc(
                null, null, List.of(""), 0, null, null, null, now, 2);
        assertThat(firstPage).extracting(Content::getId).containsExactly(id3, id2);

        Content last = firstPage.get(firstPage.size() - 1);
        List<Content> secondPage = contentRepository.findByWatcherCountDesc(
                null, null, List.of(""), 0, 1L, last.getReviewCount(),
                last.getId().toString(), now, 2);
        assertThat(secondPage).extracting(Content::getId).containsExactly(id1);
    }

    @Test
    @DisplayName("만료된 시청 세션은 watcherCount 정렬에 반영되지 않는다")
    void findByWatcherCountDesc_excludesExpiredSessions() {
        Instant now = Instant.now();
        UUID manyExpired = insertContent("Many Expired", BigDecimal.ZERO, 0, now, "MOVIE");
        UUID fewActive = insertContent("Few Active", BigDecimal.ZERO, 0, now, "MOVIE");
        insertExpiredWatchingSessions(manyExpired, 5); // 전부 만료 -> 실시간 카운트는 0
        insertWatchingSessions(fewActive, 1); // 활성 세션 1개

        List<Content> result = contentRepository.findByWatcherCountDesc(
                null, null, List.of(""), 0, null, null, null, now, 10);

        assertThat(result).extracting(Content::getId).containsExactly(fewActive, manyExpired);
    }

    @Test
    @DisplayName("콘텐츠 목록을 DTO로 변환하면 태그가 배치로 미리 로딩되어, 이후 영속성 컨텍스트가 비워져도 안전하게 읽을 수 있다")
    void findByCreatedAtDesc_tagsAreBatchFetchedAndSafeAfterContextCleared() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            UUID id = insertContent("Content " + i, BigDecimal.ZERO, 0, now.minusSeconds(i), "MOVIE");
            insertTag(id, "action");
            insertTag(id, "sf");
        }

        List<Content> result = contentRepository.findByCreatedAtDesc(null, null, List.of(""), 0, null, null, 10);

        SessionFactory sessionFactory = entityManager.getEntityManager()
                .getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<ContentDto> dtos = result.stream().map(ContentDto::from).toList();

        // 태그 5개 콘텐츠분을 배치 사이즈(100) 안에서 한 번에 가져오므로 추가 쿼리는 1개 이하여야 한다
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(1);

        entityManager.getEntityManager().clear();

        // 영속성 컨텍스트를 비워도 DTO의 tags는 이미 순수 Set으로 복사돼 있어 예외 없이 읽힌다
        assertThat(dtos).allSatisfy(dto -> assertThat(dto.tags()).containsExactlyInAnyOrder("action", "sf"));
    }

    @Test
    @DisplayName("findAllWithTagsByIdIn은 @EntityGraph로 태그를 즉시 로딩하고 원본 표기(대소문자)를 보존한다")
    void findAllWithTagsByIdIn_eagerlyFetchesTagsAndPreservesDisplayCasing() {
        Content contentA = movie().build();
        contentA.addTag("SF");
        contentA.addTag(" Drama ");
        Content contentB = movie().build();
        contentB.addTag("Action");
        entityManager.persistAndFlush(contentA);
        entityManager.persistAndFlush(contentB);
        List<UUID> ids = List.of(contentA.getId(), contentB.getId());
        entityManager.clear();

        List<Content> result = contentRepository.findAllWithTagsByIdIn(ids);

        SessionFactory sessionFactory = entityManager.getEntityManager()
                .getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        entityManager.getEntityManager().clear();

        // @EntityGraph로 이미 즉시 로딩됐으므로, 영속성 컨텍스트를 비운 뒤에도 지연 로딩 예외 없이 읽힌다
        Content foundA = result.stream().filter(c -> c.getId().equals(contentA.getId())).findFirst().orElseThrow();
        Content foundB = result.stream().filter(c -> c.getId().equals(contentB.getId())).findFirst().orElseThrow();
        assertThat(foundA.getTags()).containsExactlyInAnyOrder("SF", "Drama");
        assertThat(foundB.getTags()).containsExactly("Action");
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("findAllWithTagsByIdIn에 빈 컬렉션을 넘기면 빈 리스트를 반환한다")
    void findAllWithTagsByIdIn_emptyIds_returnsEmptyList() {
        List<Content> result = contentRepository.findAllWithTagsByIdIn(List.of());

        assertThat(result).isEmpty();
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

    // watching_session_snapshots에 활성(만료 안 됨) 세션을 activeCount개 만큼 심는다.
    // watcherCount 정렬이 이제 이 테이블의 실시간 집계 기준이므로, contents.watcher_count 컬럼 대신 이걸 쓴다.
    private void insertWatchingSessions(UUID contentId, int activeCount) {
        for (int i = 0; i < activeCount; i++) {
            insertWatchingSession(contentId, Instant.now().plusSeconds(300));
        }
    }

    // 이미 만료된(expires_at이 과거인) 세션을 심는다. 실시간 집계에서 제외되어야 함을 검증하는 데 쓴다.
    private void insertExpiredWatchingSessions(UUID contentId, int expiredCount) {
        for (int i = 0; i < expiredCount; i++) {
            insertWatchingSession(contentId, Instant.now().minusSeconds(60));
        }
    }

    private void insertWatchingSession(UUID contentId, Instant expiresAt) {
        UUID watcherId = insertUser();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO watching_session_snapshots "
                        + "(id, watcher_id, content_id, expires_at, created_at, updated_at) "
                        + "VALUES (:id, :watcherId, :contentId, :expiresAt, now(), now())")
                .setParameter("id", UUID.randomUUID())
                .setParameter("watcherId", watcherId)
                .setParameter("contentId", contentId)
                .setParameter("expiresAt", expiresAt)
                .executeUpdate();
    }

    // watching_session_snapshots.watcher_id는 users를 FK로 참조하므로 제약을 만족하는 최소한의 사용자를 만든다.
    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO users "
                        + "(id, email, password_hash, name, role, locked, created_at, updated_at) "
                        + "VALUES (:id, :email, 'stub', 'stub', 'USER', false, now(), now())")
                .setParameter("id", id)
                .setParameter("email", id + "@test.local")
                .executeUpdate();
        return id;
    }

    private void insertTag(UUID contentId, String tag) {
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO content_tags (content_id, tag, display_tag) "
                        + "VALUES (:contentId, :tag, :tag)")
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

    // ── source 역매핑 방어 (버그 픽스: enum에 없는 source 값) ───────────────────

    @Test
    @DisplayName("source에 enum에 없는 값이 저장된 행이 섞여 있어도 목록 조회는 예외 없이 반환되고, 그 행의 source만 null로 매핑된다")
    void findByWatcherCountDesc_unknownSourceValue_mappedToNullWithoutException() {
        Instant now = Instant.now();
        // 제약 도입 전(레거시 시드 등)에 enum에 없는 값이 이미 들어간 상황을 재현하기 위해
        // 제약을 일시적으로 제거한다. DDL도 트랜잭션 내에서 실행되므로 테스트 종료 시 자동 롤백된다.
        dropSourceCheckConstraint();
        UUID brokenId = insertContentWithRawSource("Broken Source", "QA_SEED", now);
        UUID validId = insertContentWithRawSource("Valid Source", "TMDB", now.minusSeconds(1));

        List<Content> result = contentRepository.findByWatcherCountDesc(
                null, null, List.of(""), 0, null, null, null, now, 10);

        assertThat(result).extracting(Content::getId).containsExactlyInAnyOrder(brokenId, validId);
        Content broken = result.stream().filter(c -> c.getId().equals(brokenId)).findFirst().orElseThrow();
        Content valid = result.stream().filter(c -> c.getId().equals(validId)).findFirst().orElseThrow();
        assertThat(broken.getSource()).isNull();
        assertThat(valid.getSource()).isEqualTo(ContentSource.TMDB);
    }

    @Test
    @DisplayName("source에 enum에 없는 값을 네이티브 INSERT로 저장하려 하면 CHECK 제약 위반이 발생한다")
    void insertContent_unknownSourceValue_violatesCheckConstraint() {
        assertThatThrownBy(() -> insertContentWithRawSource("Invalid", "QA_SEED", Instant.now()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("source가 NULL이면 CHECK 제약과 무관하게 정상 저장된다")
    void insertContent_nullSource_savesSuccessfully() {
        UUID id = insertContentWithRawSource("Null Source", null, Instant.now());
        entityManager.getEntityManager().clear();

        Content found = contentRepository.findById(id).orElseThrow();

        assertThat(found.getSource()).isNull();
    }

    private UUID insertContentWithRawSource(String title, String rawSourceValue, Instant createdAt) {
        UUID id = UUID.randomUUID();
        String sourceLiteral = rawSourceValue == null ? "NULL" : "'" + rawSourceValue + "'";
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO contents "
                        + "(id, created_at, updated_at, type, source, title, description, average_rating, review_count, watcher_count) "
                        + "VALUES (:id, :createdAt, :createdAt, 'MOVIE', " + sourceLiteral + ", :title, 'description', 0.0, 0, 0)")
                .setParameter("id", id)
                .setParameter("createdAt", createdAt)
                .setParameter("title", title)
                .executeUpdate();
        return id;
    }

    private void dropSourceCheckConstraint() {
        entityManager.getEntityManager()
                .createNativeQuery("ALTER TABLE contents DROP CONSTRAINT ck_contents_source")
                .executeUpdate();
    }

    // ── findByIdForUpdate ────────────────────────────────────────────────────

    @Test
    @DisplayName("findByIdForUpdate는 존재하는 콘텐츠를 락과 함께 조회한다")
    void findByIdForUpdate_existingContent_returnsContent() {
        Content content = entityManager.persistAndFlush(movie().build());

        Optional<Content> result = contentRepository.findByIdForUpdate(content.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(content.getId());
    }

    @Test
    @DisplayName("findByIdForUpdate는 소프트 삭제된 콘텐츠에 대해 빈 Optional을 반환한다")
    void findByIdForUpdate_softDeletedContent_returnsEmpty() {
        Content content = entityManager.persistAndFlush(movie().build());
        markDeleted(content.getId());
        entityManager.clear();

        Optional<Content> result = contentRepository.findByIdForUpdate(content.getId());

        assertThat(result).isEmpty();
    }

    // ── findByIdForUpdateWithLockTimeout ────────────────────────────────────

    @Test
    @DisplayName("findByIdForUpdateWithLockTimeout는 락 타임아웃 설정 후 콘텐츠를 락과 함께 조회한다")
    void findByIdForUpdateWithLockTimeout_returnsContent() {
        Content content = entityManager.persistAndFlush(movie().build());

        Optional<Content> result = contentRepository.findByIdForUpdateWithLockTimeout(content.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(content.getId());
    }

    // ── findBySourceAndExternalId ────────────────────────────────────────────

    @Test
    @DisplayName("findBySourceAndExternalId는 저장된 (source, externalId) 조합의 콘텐츠를 반환한다")
    void findBySourceAndExternalId_existingContent_returnsContent() {
        Content content = entityManager.persistAndFlush(
                movie().source(ContentSource.TMDB).externalId("603").build());
        entityManager.clear();

        Optional<Content> result = contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "603");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(content.getId());
    }

    @Test
    @DisplayName("findBySourceAndExternalId는 존재하지 않는 조합이면 빈 Optional을 반환한다")
    void findBySourceAndExternalId_notFound_returnsEmpty() {
        Optional<Content> result = contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "no-such-id");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findBySourceAndExternalId는 소프트 삭제된 콘텐츠에 대해 빈 Optional을 반환한다")
    void findBySourceAndExternalId_softDeletedContent_returnsEmpty() {
        Content content = entityManager.persistAndFlush(
                movie().source(ContentSource.TMDB).externalId("603").build());
        markDeleted(content.getId());
        entityManager.clear();

        Optional<Content> result = contentRepository.findBySourceAndExternalId(ContentSource.TMDB, "603");

        assertThat(result).isEmpty();
    }

    // ── findBySource ────────────────────────────────────────────────────────

    @Test
    @DisplayName("findBySource는 요청한 소스의 콘텐츠만 반환한다")
    void findBySource_returnsOnlyMatchingSourceContents() {
        Instant now = Instant.now();
        UUID tmdb1 = insertContentWithRawSource("TMDB 1", "TMDB", now);
        UUID tmdb2 = insertContentWithRawSource("TMDB 2", "TMDB", now.minusSeconds(1));
        insertContentWithRawSource("SportsDB 1", "SPORTS_DB", now.minusSeconds(2));

        Slice<Content> result = contentRepository.findBySource(ContentSource.TMDB, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Content::getId)
                .containsExactlyInAnyOrder(tmdb1, tmdb2);
    }

    @Test
    @DisplayName("findBySource는 소프트 삭제된 콘텐츠를 제외한다")
    void findBySource_excludesSoftDeletedContent() {
        Instant now = Instant.now();
        UUID kept = insertContentWithRawSource("TMDB kept", "TMDB", now);
        UUID deleted = insertContentWithRawSource("TMDB deleted", "TMDB", now.minusSeconds(1));
        markDeleted(deleted);

        Slice<Content> result = contentRepository.findBySource(ContentSource.TMDB, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Content::getId).containsExactly(kept);
    }

    @Test
    @DisplayName("findBySource는 커서 페이지네이션으로 2페이지에 걸쳐 전부 조회된다")
    void findBySource_pagination_acrossTwoPages() {
        Instant now = Instant.now();
        UUID id1 = insertContentWithRawSource("T1", "TMDB", now.minusSeconds(3));
        UUID id2 = insertContentWithRawSource("T2", "TMDB", now.minusSeconds(2));
        UUID id3 = insertContentWithRawSource("T3", "TMDB", now.minusSeconds(1));

        Pageable firstPageable = PageRequest.of(0, 2);
        Slice<Content> firstPage = contentRepository.findBySource(ContentSource.TMDB, firstPageable);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();

        Slice<Content> secondPage = contentRepository.findBySource(ContentSource.TMDB, firstPageable.next());
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.hasNext()).isFalse();

        assertThat(List.of(firstPage.getContent(), secondPage.getContent()).stream()
                .flatMap(List::stream)
                .map(Content::getId)
                .toList())
                .containsExactlyInAnyOrder(id1, id2, id3);
    }

    // ── findBySourceAndExternalIdIncludingDeleted ───────────────────────────

    @Test
    @DisplayName("findBySourceAndExternalIdIncludingDeleted는 삭제되지 않은 콘텐츠를 반환한다")
    void findBySourceAndExternalIdIncludingDeleted_activeContent_returnsContent() {
        Content content = entityManager.persistAndFlush(
                movie().source(ContentSource.TMDB).externalId("603").build());
        entityManager.clear();

        Optional<Content> result = contentRepository.findBySourceAndExternalIdIncludingDeleted(
                ContentSource.TMDB.name(), "603");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(content.getId());
    }

    @Test
    @DisplayName("findBySourceAndExternalIdIncludingDeleted는 소프트 삭제된 콘텐츠도 반환한다")
    void findBySourceAndExternalIdIncludingDeleted_softDeletedContent_returnsContent() {
        Content content = entityManager.persistAndFlush(
                movie().source(ContentSource.TMDB).externalId("603").build());
        markDeleted(content.getId());
        entityManager.clear();

        Optional<Content> result = contentRepository.findBySourceAndExternalIdIncludingDeleted(
                ContentSource.TMDB.name(), "603");

        assertThat(result).isPresent();
        assertThat(result.get().getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("findBySourceAndExternalIdIncludingDeleted는 존재하지 않는 조합이면 빈 Optional을 반환한다")
    void findBySourceAndExternalIdIncludingDeleted_notFound_returnsEmpty() {
        Optional<Content> result = contentRepository.findBySourceAndExternalIdIncludingDeleted(
                ContentSource.TMDB.name(), "no-such-id");

        assertThat(result).isEmpty();
    }

    // ── refreshReviewAggregate ─────────────────────────────────────────────

    @Test
    @DisplayName("리뷰가 없으면 refreshReviewAggregate는 평균 0.0, 개수 0으로 반영한다")
    void refreshReviewAggregate_noReviews_setsZero() {
        Content content = entityManager.persistAndFlush(movie().build());

        contentRepository.refreshReviewAggregate(content.getId());
        entityManager.clear();

        Content result = contentRepository.findById(content.getId()).orElseThrow();
        assertThat(result.getAverageRating()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getReviewCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("리뷰가 있으면 refreshReviewAggregate는 평균과 개수를 정확히 반영한다")
    void refreshReviewAggregate_withReviews_setsAverageAndCount() {
        Content content = entityManager.persistAndFlush(movie().build());
        insertReview(content.getId(), new BigDecimal("3.0"));
        insertReview(content.getId(), new BigDecimal("4.0"));
        insertReview(content.getId(), new BigDecimal("5.0"));

        contentRepository.refreshReviewAggregate(content.getId());
        entityManager.clear();

        Content result = contentRepository.findById(content.getId()).orElseThrow();
        assertThat(result.getAverageRating()).isEqualByComparingTo("4.0");
        assertThat(result.getReviewCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("소수 둘째 자리 이하 평균은 반올림 경계값(x.x5)에서 HALF_UP으로 올림된다")
    void refreshReviewAggregate_halfUpBoundary_roundsUp() {
        Content content = entityManager.persistAndFlush(movie().build());
        insertReview(content.getId(), new BigDecimal("4.0"));
        insertReview(content.getId(), new BigDecimal("4.0"));
        insertReview(content.getId(), new BigDecimal("4.0"));
        insertReview(content.getId(), new BigDecimal("5.0"));
        // 평균 = (4+4+4+5)/4 = 4.25 → 반올림 시 4.3

        contentRepository.refreshReviewAggregate(content.getId());
        entityManager.clear();

        Content result = contentRepository.findById(content.getId()).orElseThrow();
        assertThat(result.getAverageRating()).isEqualByComparingTo("4.3");
    }

    @Test
    @DisplayName("소프트 삭제된 콘텐츠는 refreshReviewAggregate가 갱신하지 않는다")
    void refreshReviewAggregate_softDeletedContent_skipsUpdate() {
        Content content = entityManager.persistAndFlush(movie().build());
        UUID contentId = content.getId();
        insertReview(contentId, new BigDecimal("5.0"));
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE contents SET deleted_at = now() WHERE id = :id")
                .setParameter("id", contentId)
                .executeUpdate();
        entityManager.clear();

        contentRepository.refreshReviewAggregate(contentId);

        Content result = (Content) entityManager.getEntityManager()
                .createNativeQuery("SELECT * FROM contents WHERE id = :id", Content.class)
                .setParameter("id", contentId)
                .getSingleResult();
        assertThat(result.getReviewCount()).isEqualTo(0L);
        assertThat(result.getAverageRating()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private UUID insertReview(UUID contentId, BigDecimal rating) {
        UUID authorId = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO users "
                        + "(id, created_at, updated_at, email, password_hash, name, role) "
                        + "VALUES (:id, now(), now(), :email, 'hash', 'tester', 'USER')")
                .setParameter("id", authorId)
                .setParameter("email", authorId + "@test.com")
                .executeUpdate();
        UUID reviewId = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO reviews "
                        + "(id, created_at, updated_at, author_id, content_id, text, rating) "
                        + "VALUES (:id, now(), now(), :authorId, :contentId, 'text', :rating)")
                .setParameter("id", reviewId)
                .setParameter("authorId", authorId)
                .setParameter("contentId", contentId)
                .setParameter("rating", rating)
                .executeUpdate();
        return reviewId;
    }
}
