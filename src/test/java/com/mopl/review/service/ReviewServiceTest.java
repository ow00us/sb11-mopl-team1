package com.mopl.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mopl.content.entity.Content;
import com.mopl.content.entity.ContentType;
import com.mopl.content.repository.ContentRepository;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.util.CursorUtils;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewDto;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.entity.Review;
import com.mopl.review.repository.ReviewRepository;
import com.mopl.user.entity.User;
import com.mopl.user.entity.UserRole;
import com.mopl.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    ReviewRepository reviewRepository;

    @Mock
    ContentRepository contentRepository;

    @Mock
    UserRepository userRepository;

    @InjectMocks
    ReviewServiceImpl reviewService;

    private static final UUID CONTENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID AUTHOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID REVIEW_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("리뷰 생성 시 저장 후 콘텐츠 집계를 갱신하고 ReviewDto를 반환한다")
    void create_success() {
        ReviewCreateRequest request = new ReviewCreateRequest(CONTENT_ID, "재밌어요", new BigDecimal("4.5"));
        Content content = movie();
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(content));
        when(contentRepository.findByIdForUpdate(CONTENT_ID)).thenReturn(Optional.of(content));
        when(reviewRepository.existsByAuthorIdAndContentId(AUTHOR_ID, CONTENT_ID)).thenReturn(false);
        when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(savedUser(AUTHOR_ID, "닉네임", null)));

        ReviewDto result = reviewService.create(request, AUTHOR_ID);

        assertThat(result.text()).isEqualTo("재밌어요");
        assertThat(result.rating()).isEqualByComparingTo("4.5");
        assertThat(result.author().userId()).isEqualTo(AUTHOR_ID);
        verify(reviewRepository).saveAndFlush(any(Review.class));
        verify(contentRepository).refreshReviewAggregate(CONTENT_ID);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠에 리뷰 생성 시 CONTENT_NOT_FOUND 예외가 발생한다")
    void create_fail_contentNotFound() {
        ReviewCreateRequest request = new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("3.0"));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.create(request, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONTENT_NOT_FOUND);

        verify(reviewRepository, never()).existsByAuthorIdAndContentId(any(), any());
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("이미 작성한 리뷰가 있으면 REVIEW_DUPLICATE 예외가 발생한다")
    void create_fail_duplicate_preCheck() {
        ReviewCreateRequest request = new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("3.0"));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(movie()));
        when(reviewRepository.existsByAuthorIdAndContentId(AUTHOR_ID, CONTENT_ID)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.create(request, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_DUPLICATE);

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("사전 체크를 통과했지만 DB unique_violation(23505)이 나면 REVIEW_DUPLICATE 예외로 변환한다")
    void create_fail_duplicate_dbConstraint() {
        ReviewCreateRequest request = new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("3.0"));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(movie()));
        when(reviewRepository.existsByAuthorIdAndContentId(AUTHOR_ID, CONTENT_ID)).thenReturn(false);
        SQLException uniqueViolation = new SQLException("dup", "23505");
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(new DataIntegrityViolationException("dup", uniqueViolation));

        assertThatThrownBy(() -> reviewService.create(request, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_DUPLICATE);

        verify(contentRepository, never()).refreshReviewAggregate(any());
    }

    @Test
    @DisplayName("Spring DuplicateKeyException이 나도 REVIEW_DUPLICATE 예외로 변환한다")
    void create_fail_duplicate_springDuplicateKeyException() {
        ReviewCreateRequest request = new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("3.0"));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(movie()));
        when(reviewRepository.existsByAuthorIdAndContentId(AUTHOR_ID, CONTENT_ID)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> reviewService.create(request, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_DUPLICATE);
    }

    @Test
    @DisplayName("중복이 아닌 제약 위반(CHECK 등)은 REVIEW_DUPLICATE로 변환하지 않고 원본 예외를 그대로 전파한다")
    void create_fail_nonDuplicateConstraintViolation_propagatesOriginalException() {
        ReviewCreateRequest request = new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("3.0"));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(movie()));
        when(reviewRepository.existsByAuthorIdAndContentId(AUTHOR_ID, CONTENT_ID)).thenReturn(false);
        SQLException checkViolation = new SQLException("check", "23514");
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(new DataIntegrityViolationException("check", checkViolation));

        assertThatThrownBy(() -> reviewService.create(request, AUTHOR_ID))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("저장 후 작성자 정보를 찾을 수 없으면 RESOURCE_NOT_FOUND 예외가 발생한다")
    void create_fail_authorNotFoundAfterSave() {
        ReviewCreateRequest request = new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("3.0"));
        when(contentRepository.findById(CONTENT_ID)).thenReturn(Optional.of(movie()));
        when(reviewRepository.existsByAuthorIdAndContentId(AUTHOR_ID, CONTENT_ID)).thenReturn(false);
        when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.create(request, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("작성자가 수정하면 저장 후 콘텐츠 집계를 갱신하고 ReviewDto를 반환한다")
    void update_success() {
        Review review = savedReview(REVIEW_ID, AUTHOR_ID, CONTENT_ID, "원래", new BigDecimal("3.0"));
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
        when(contentRepository.findByIdForUpdate(CONTENT_ID)).thenReturn(Optional.of(movie()));
        when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(savedUser(AUTHOR_ID, "닉네임", null)));

        ReviewDto result = reviewService.update(
                REVIEW_ID, new ReviewUpdateRequest("새 리뷰", new BigDecimal("4.0")), AUTHOR_ID);

        assertThat(result.text()).isEqualTo("새 리뷰");
        assertThat(result.rating()).isEqualByComparingTo("4.0");
        verify(reviewRepository).saveAndFlush(review);
        verify(contentRepository).refreshReviewAggregate(CONTENT_ID);
    }

    @Test
    @DisplayName("존재하지 않는 리뷰 수정 시 REVIEW_NOT_FOUND 예외가 발생한다")
    void update_fail_notFound() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.update(
                REVIEW_ID, new ReviewUpdateRequest("t", null), AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
    }

    @Test
    @DisplayName("작성자가 아니면 수정 시 FORBIDDEN 예외가 발생한다")
    void update_fail_forbidden() {
        Review review = savedReview(REVIEW_ID, AUTHOR_ID, CONTENT_ID, "원래", new BigDecimal("3.0"));
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.update(
                REVIEW_ID, new ReviewUpdateRequest("t", null), OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(reviewRepository, never()).saveAndFlush(any());
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("작성자가 삭제하면 삭제 후 콘텐츠 집계를 갱신한다")
    void delete_success() {
        Review review = savedReview(REVIEW_ID, AUTHOR_ID, CONTENT_ID, "text", new BigDecimal("3.0"));
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));
        when(contentRepository.findByIdForUpdate(CONTENT_ID)).thenReturn(Optional.of(movie()));

        reviewService.delete(REVIEW_ID, AUTHOR_ID);

        verify(reviewRepository).delete(review);
        verify(reviewRepository).flush();
        verify(contentRepository).refreshReviewAggregate(CONTENT_ID);
    }

    @Test
    @DisplayName("존재하지 않는 리뷰 삭제 시 REVIEW_NOT_FOUND 예외가 발생한다")
    void delete_fail_notFound() {
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.delete(REVIEW_ID, AUTHOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REVIEW_NOT_FOUND);
    }

    @Test
    @DisplayName("작성자가 아니면 삭제 시 FORBIDDEN 예외가 발생한다")
    void delete_fail_forbidden() {
        Review review = savedReview(REVIEW_ID, AUTHOR_ID, CONTENT_ID, "text", new BigDecimal("3.0"));
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.delete(REVIEW_ID, OTHER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);

        verify(reviewRepository, never()).delete(any());
    }

    // ── getList ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getList 호출 시 페이지 안 작성자들을 배치 조회해서 ReviewDto에 채운다 (N+1 방지)")
    void getList_batchFetchesAuthors() {
        Review review1 = savedReview(UUID.randomUUID(), AUTHOR_ID, CONTENT_ID, "t1", new BigDecimal("3.0"));
        Review review2 = savedReview(UUID.randomUUID(), OTHER_ID, CONTENT_ID, "t2", new BigDecimal("4.0"));
        when(reviewRepository.findByCreatedAtDesc(eq(CONTENT_ID), any(), any(), eq(11)))
                .thenReturn(List.of(review1, review2));
        when(reviewRepository.countByFilter(CONTENT_ID)).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                savedUser(AUTHOR_ID, "A", null), savedUser(OTHER_ID, "B", null)));

        CursorResponse<ReviewDto> result = reviewService.getList(
                CONTENT_ID, null, null, 10, "createdAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.data()).extracting(dto -> dto.author().userId())
                .containsExactlyInAnyOrder(AUTHOR_ID, OTHER_ID);
        verify(userRepository).findAllById(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("sortBy가 rating이면 findByRatingDesc를 호출한다")
    void getList_sortByRating_callsFindByRatingDesc() {
        when(reviewRepository.findByRatingDesc(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(reviewRepository.countByFilter(any())).thenReturn(0L);

        reviewService.getList(null, null, null, 10, "rating", "DESCENDING");

        verify(reviewRepository).findByRatingDesc(isNull(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("sortBy가 rating이고 방향이 ASCENDING이면 findByRatingAsc를 호출한다")
    void getList_sortByRatingAscending_callsFindByRatingAsc() {
        when(reviewRepository.findByRatingAsc(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(reviewRepository.countByFilter(any())).thenReturn(0L);

        reviewService.getList(null, null, null, 10, "rating", "ASCENDING");

        verify(reviewRepository).findByRatingAsc(isNull(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("sortBy가 createdAt이고 방향이 ASCENDING이면 findByCreatedAtAsc를 호출한다")
    void getList_sortByCreatedAtAscending_callsFindByCreatedAtAsc() {
        when(reviewRepository.findByCreatedAtAsc(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(reviewRepository.countByFilter(any())).thenReturn(0L);

        reviewService.getList(null, null, null, 10, "createdAt", "ASCENDING");

        verify(reviewRepository).findByCreatedAtAsc(isNull(), isNull(), isNull(), eq(11));
    }

    @Test
    @DisplayName("잘못된 형식의 cursor 값이면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidCursor() {
        assertThatThrownBy(() -> reviewService.getList(
                null, "not-a-valid-cursor!!", UUID.randomUUID(), 10, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("rating 정렬 시 다음 페이지가 있으면 rating 기반 nextCursor를 반환한다")
    void getList_ratingSort_hasNextPage_returnsRatingCursor() {
        Review r1 = savedReview(UUID.randomUUID(), AUTHOR_ID, CONTENT_ID, "t1", new BigDecimal("3.0"));
        Review r2 = savedReview(UUID.randomUUID(), AUTHOR_ID, CONTENT_ID, "t2", new BigDecimal("4.0"));
        Review r3 = savedReview(UUID.randomUUID(), AUTHOR_ID, CONTENT_ID, "t3", new BigDecimal("5.0"));
        when(reviewRepository.findByRatingDesc(any(), any(), any(), eq(3))).thenReturn(List.of(r1, r2, r3));
        when(reviewRepository.countByFilter(any())).thenReturn(5L);
        when(userRepository.findAllById(any())).thenReturn(List.of(savedUser(AUTHOR_ID, "A", null)));

        CursorResponse<ReviewDto> result = reviewService.getList(
                null, null, null, 2, "rating", "DESCENDING");

        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(CursorUtils.encodeBigDecimal(r2.getRating()));
    }

    @Test
    @DisplayName("페이지 안 리뷰의 작성자가 조회되지 않으면 대체 요약값(알 수 없는 사용자)으로 채워진다")
    void getList_authorNotFound_fallsBackToPlaceholder() {
        Review review = savedReview(UUID.randomUUID(), AUTHOR_ID, CONTENT_ID, "t1", new BigDecimal("3.0"));
        when(reviewRepository.findByCreatedAtDesc(any(), any(), any(), anyInt())).thenReturn(List.of(review));
        when(reviewRepository.countByFilter(any())).thenReturn(1L);
        when(userRepository.findAllById(any())).thenReturn(List.of());

        CursorResponse<ReviewDto> result = reviewService.getList(null, null, null, 10, "createdAt", "DESCENDING");

        assertThat(result.data()).hasSize(1);
        assertThat(result.data().get(0).author().userId()).isEqualTo(AUTHOR_ID);
        assertThat(result.data().get(0).author().name()).isEqualTo("알 수 없는 사용자");
    }

    @Test
    @DisplayName("limit이 1 미만이면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_limitTooSmall() {
        assertThatThrownBy(() -> reviewService.getList(null, null, null, 0, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(reviewRepository);
    }

    @Test
    @DisplayName("limit이 100을 초과하면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_limitTooLarge() {
        assertThatThrownBy(() -> reviewService.getList(null, null, null, 101, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("인식되지 않는 sortBy 값이면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidSortBy() {
        assertThatThrownBy(() -> reviewService.getList(null, null, null, 10, "invalid", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(reviewRepository);
    }

    @Test
    @DisplayName("인식되지 않는 sortDirection 값이면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_invalidSortDirection() {
        assertThatThrownBy(() -> reviewService.getList(null, null, null, 10, "createdAt", "invalid"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verifyNoInteractions(reviewRepository);
    }

    @Test
    @DisplayName("cursor만 있고 idAfter가 없으면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_cursorWithoutIdAfter() {
        String validCursor = CursorUtils.encodeInstant(Instant.now());

        assertThatThrownBy(() -> reviewService.getList(
                null, validCursor, null, 10, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("idAfter만 있고 cursor가 없으면 INVALID_INPUT 예외가 발생한다")
    void getList_fail_idAfterWithoutCursor() {
        assertThatThrownBy(() -> reviewService.getList(
                null, null, UUID.randomUUID(), 10, "createdAt", "DESCENDING"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("createdAt 정렬 시 다음 페이지가 있으면 hasNext true와 nextCursor를 반환한다")
    void getList_hasNextPage() {
        Review r1 = savedReview(UUID.randomUUID(), AUTHOR_ID, CONTENT_ID, "t1", new BigDecimal("3.0"));
        Review r2 = savedReview(UUID.randomUUID(), AUTHOR_ID, CONTENT_ID, "t2", new BigDecimal("3.0"));
        Review r3 = savedReview(UUID.randomUUID(), AUTHOR_ID, CONTENT_ID, "t3", new BigDecimal("3.0"));
        when(reviewRepository.findByCreatedAtDesc(any(), any(), any(), eq(3))).thenReturn(List.of(r1, r2, r3));
        when(reviewRepository.countByFilter(any())).thenReturn(5L);
        when(userRepository.findAllById(any())).thenReturn(List.of(savedUser(AUTHOR_ID, "A", null)));

        CursorResponse<ReviewDto> result = reviewService.getList(
                null, null, null, 2, "createdAt", "DESCENDING");

        assertThat(result.data()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
        assertThat(result.nextIdAfter()).isNotNull();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Content movie() {
        return Content.builder()
                .type(ContentType.MOVIE)
                .title("제목")
                .description("설명")
                .build();
    }

    private Review savedReview(UUID id, UUID authorId, UUID contentId, String text, BigDecimal rating) {
        Review review = Review.builder()
                .authorId(authorId)
                .contentId(contentId)
                .text(text)
                .rating(rating)
                .build();
        ReflectionTestUtils.setField(review, "id", id);
        ReflectionTestUtils.setField(review, "createdAt", Instant.now());
        return review;
    }

    private User savedUser(UUID id, String name, String profileImageUrl) {
        User user = User.builder()
                .email(id + "@test.com")
                .passwordHash("hash")
                .name(name)
                .profileImageUrl(profileImageUrl)
                .role(UserRole.USER)
                .locked(false)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}