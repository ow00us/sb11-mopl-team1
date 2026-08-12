package com.mopl.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.review.entity.Review;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
class ReviewRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    TestEntityManager entityManager;

    @Autowired
    ReviewRepository reviewRepository;

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO users "
                        + "(id, created_at, updated_at, email, password_hash, name, role) "
                        + "VALUES (:id, now(), now(), :email, 'hash', 'tester', 'USER')")
                .setParameter("id", id)
                .setParameter("email", id + "@test.com")
                .executeUpdate();
        return id;
    }

    private UUID insertContent() {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO contents "
                        + "(id, created_at, updated_at, type, title, description) "
                        + "VALUES (:id, now(), now(), 'MOVIE', 'title', 'description')")
                .setParameter("id", id)
                .executeUpdate();
        return id;
    }

    @Test
    @DisplayName("저장한 리뷰를 ID로 조회하면 author_id·content_id·text·rating이 그대로 반환된다")
    void save_and_findById_success() {
        // given
        UUID authorId = insertUser();
        UUID contentId = insertContent();
        Review review = Review.builder()
                .authorId(authorId)
                .contentId(contentId)
                .text("정말 재밌게 봤어요")
                .rating(new BigDecimal("4.5"))
                .build();

        // when
        Review saved = entityManager.persistAndFlush(review);
        UUID reviewId = saved.getId();
        entityManager.clear();
        Optional<Review> result = reviewRepository.findById(reviewId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getAuthorId()).isEqualTo(authorId);
        assertThat(result.get().getContentId()).isEqualTo(contentId);
        assertThat(result.get().getText()).isEqualTo("정말 재밌게 봤어요");
        assertThat(result.get().getRating()).isEqualByComparingTo("4.5");
        assertThat(result.get().getCreatedAt()).isNotNull();
        assertThat(result.get().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("동일한 (author_id, content_id) 조합으로 저장하면 제약 위반이 발생한다")
    void duplicate_author_content_violates_constraint() {
        // given
        UUID authorId = insertUser();
        UUID contentId = insertContent();
        entityManager.persistAndFlush(Review.builder()
                .authorId(authorId)
                .contentId(contentId)
                .text("first review")
                .rating(new BigDecimal("3.0"))
                .build());
        Review duplicate = Review.builder()
                .authorId(authorId)
                .contentId(contentId)
                .text("second review")
                .rating(new BigDecimal("4.0"))
                .build();

        // when & then
        assertThatThrownBy(() -> entityManager.persistAndFlush(duplicate))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("존재하지 않는 content_id로 저장하면 외래키 제약 위반이 발생한다")
    void nonexistent_content_id_violates_fk_constraint() {
        // given
        UUID authorId = insertUser();
        Review review = Review.builder()
                .authorId(authorId)
                .contentId(UUID.randomUUID())
                .text("text")
                .rating(new BigDecimal("3.0"))
                .build();

        // when & then
        assertThatThrownBy(() -> entityManager.persistAndFlush(review))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("존재하지 않는 author_id로 저장하면 외래키 제약 위반이 발생한다")
    void nonexistent_author_id_violates_fk_constraint() {
        // given
        UUID contentId = insertContent();
        Review review = Review.builder()
                .authorId(UUID.randomUUID())
                .contentId(contentId)
                .text("text")
                .rating(new BigDecimal("3.0"))
                .build();

        // when & then
        assertThatThrownBy(() -> entityManager.persistAndFlush(review))
                .isInstanceOf(RuntimeException.class);
    }

    // ── existsByAuthorIdAndContentId ────────────────────────────────────────

    @Test
    @DisplayName("existsByAuthorIdAndContentId는 리뷰가 존재하면 true, 없으면 false를 반환한다")
    void existsByAuthorIdAndContentId_reflectsExistence() {
        // given
        UUID authorId = insertUser();
        UUID contentId = insertContent();
        entityManager.persistAndFlush(Review.builder()
                .authorId(authorId).contentId(contentId).text("text").rating(new BigDecimal("3.0")).build());

        // when & then
        assertThat(reviewRepository.existsByAuthorIdAndContentId(authorId, contentId)).isTrue();
        assertThat(reviewRepository.existsByAuthorIdAndContentId(authorId, UUID.randomUUID())).isFalse();
    }

    // ── findByAuthorIdAndContentId ──────────────────────────────────────────

    @Test
    @DisplayName("findByAuthorIdAndContentId는 리뷰가 존재하면 해당 리뷰를 담은 Optional을 반환한다")
    void findByAuthorIdAndContentId_reviewExists_returnsReview() {
        // given
        UUID authorId = insertUser();
        UUID contentId = insertContent();
        Review saved = entityManager.persistAndFlush(Review.builder()
                .authorId(authorId).contentId(contentId).text("text").rating(new BigDecimal("3.0")).build());
        entityManager.clear();

        // when
        Optional<Review> result = reviewRepository.findByAuthorIdAndContentId(authorId, contentId);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    @Test
    @DisplayName("findByAuthorIdAndContentId는 리뷰가 없으면 빈 Optional을 반환한다")
    void findByAuthorIdAndContentId_reviewMissing_returnsEmpty() {
        // given
        UUID authorId = insertUser();
        UUID contentId = insertContent();

        // when
        Optional<Review> result = reviewRepository.findByAuthorIdAndContentId(authorId, contentId);

        // then
        assertThat(result).isEmpty();
    }

    // ── countByFilter ──────────────────────────────────────────────────────

    @Test
    @DisplayName("countByFilter는 contentId 필터에 맞는 리뷰 개수를 센다")
    void countByFilter_countsMatchingReviews() {
        // given
        UUID contentA = insertContent();
        UUID contentB = insertContent();
        insertReview(insertUser(), contentA, new BigDecimal("3.0"), Instant.now());
        insertReview(insertUser(), contentA, new BigDecimal("4.0"), Instant.now());
        insertReview(insertUser(), contentB, new BigDecimal("5.0"), Instant.now());

        // when & then
        assertThat(reviewRepository.countByFilter(contentA)).isEqualTo(2L);
        assertThat(reviewRepository.countByFilter(null)).isEqualTo(3L);
    }

    // ── createdAt 정렬 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("contentId로 필터링하면 해당 콘텐츠의 리뷰만 조회된다")
    void findByCreatedAtDesc_filtersByContentId() {
        // given
        UUID contentA = insertContent();
        UUID contentB = insertContent();
        Instant now = Instant.now();
        UUID reviewA = insertReview(insertUser(), contentA, new BigDecimal("3.0"), now);
        insertReview(insertUser(), contentB, new BigDecimal("4.0"), now.minusSeconds(1));

        // when
        List<Review> result = reviewRepository.findByCreatedAtDesc(contentA, null, null, 10);

        // then
        assertThat(result).extracting(Review::getId).containsExactly(reviewA);
    }

    @Test
    @DisplayName("createdAt DESC 정렬로 커서 페이지네이션 시 2페이지에 걸쳐 전부 조회된다")
    void findByCreatedAtDesc_pagination_acrossTwoPages() {
        // given
        UUID contentId = insertContent();
        Instant base = Instant.now();
        UUID id1 = insertReview(insertUser(), contentId, new BigDecimal("3.0"), base.minusSeconds(3));
        UUID id2 = insertReview(insertUser(), contentId, new BigDecimal("3.0"), base.minusSeconds(2));
        UUID id3 = insertReview(insertUser(), contentId, new BigDecimal("3.0"), base.minusSeconds(1));

        // when
        List<Review> firstPage = reviewRepository.findByCreatedAtDesc(contentId, null, null, 2);
        assertThat(firstPage).extracting(Review::getId).containsExactly(id3, id2);

        Review last = firstPage.get(firstPage.size() - 1);
        List<Review> secondPage = reviewRepository.findByCreatedAtDesc(
                contentId, last.getCreatedAt(), last.getId(), 2);

        // then
        assertThat(secondPage).extracting(Review::getId).containsExactly(id1);
    }

    @Test
    @DisplayName("createdAt ASC 정렬은 오래된 순으로 반환된다")
    void findByCreatedAtAsc_ordersFromOldest() {
        // given
        UUID contentId = insertContent();
        Instant base = Instant.now();
        UUID id1 = insertReview(insertUser(), contentId, new BigDecimal("3.0"), base.minusSeconds(2));
        UUID id2 = insertReview(insertUser(), contentId, new BigDecimal("3.0"), base.minusSeconds(1));

        // when
        List<Review> result = reviewRepository.findByCreatedAtAsc(contentId, null, null, 10);

        // then
        assertThat(result).extracting(Review::getId).containsExactly(id1, id2);
    }

    // ── rating 정렬 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("rating DESC 정렬로 커서 페이지네이션 시 2페이지에 걸쳐 전부 조회된다")
    void findByRatingDesc_pagination_acrossTwoPages() {
        // given
        UUID contentId = insertContent();
        Instant now = Instant.now();
        UUID id1 = insertReview(insertUser(), contentId, new BigDecimal("1.0"), now);
        UUID id2 = insertReview(insertUser(), contentId, new BigDecimal("3.0"), now);
        UUID id3 = insertReview(insertUser(), contentId, new BigDecimal("5.0"), now);

        // when
        List<Review> firstPage = reviewRepository.findByRatingDesc(contentId, null, null, 2);
        assertThat(firstPage).extracting(Review::getId).containsExactly(id3, id2);

        Review last = firstPage.get(firstPage.size() - 1);
        List<Review> secondPage = reviewRepository.findByRatingDesc(
                contentId, last.getRating(), last.getId(), 2);

        // then
        assertThat(secondPage).extracting(Review::getId).containsExactly(id1);
    }

    @Test
    @DisplayName("rating ASC 정렬은 낮은 평점부터 반환된다")
    void findByRatingAsc_ordersFromLowest() {
        // given
        UUID contentId = insertContent();
        Instant now = Instant.now();
        UUID id1 = insertReview(insertUser(), contentId, new BigDecimal("1.0"), now);
        UUID id2 = insertReview(insertUser(), contentId, new BigDecimal("4.0"), now);

        // when
        List<Review> result = reviewRepository.findByRatingAsc(contentId, null, null, 10);

        // then
        assertThat(result).extracting(Review::getId).containsExactly(id1, id2);
    }

    private UUID insertReview(UUID authorId, UUID contentId, BigDecimal rating, Instant createdAt) {
        UUID id = UUID.randomUUID();
        entityManager.getEntityManager()
                .createNativeQuery("INSERT INTO reviews "
                        + "(id, created_at, updated_at, author_id, content_id, text, rating) "
                        + "VALUES (:id, :createdAt, :createdAt, :authorId, :contentId, 'review text', :rating)")
                .setParameter("id", id)
                .setParameter("createdAt", createdAt)
                .setParameter("authorId", authorId)
                .setParameter("contentId", contentId)
                .setParameter("rating", rating)
                .executeUpdate();
        return id;
    }
}