package com.mopl.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mopl.global.config.JpaConfig;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.review.entity.Review;
import java.math.BigDecimal;
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
    @DisplayName("rating이 null이면 생성 시점에 BusinessException이 발생한다")
    void null_rating_throws_business_exception() {
        assertThatThrownBy(() -> Review.builder()
                .authorId(UUID.randomUUID())
                .contentId(UUID.randomUUID())
                .text("text")
                .rating(null)
                .build())
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @ParameterizedTest
    @DisplayName("rating이 범위를 벗어나거나 0.5 단위가 아니면 생성 시점에 BusinessException이 발생한다")
    @ValueSource(strings = {"-0.1", "5.1", "1.2", "3.3"})
    void invalid_rating_throws_business_exception(String invalidRating) {
        assertThatThrownBy(() -> Review.builder()
                .authorId(UUID.randomUUID())
                .contentId(UUID.randomUUID())
                .text("text")
                .rating(new BigDecimal(invalidRating))
                .build())
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
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
}