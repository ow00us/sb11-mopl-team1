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
}