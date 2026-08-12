package com.mopl.review.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.common.UserSummary;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.review.dto.ReviewCreateRequest;
import com.mopl.review.dto.ReviewDto;
import com.mopl.review.dto.ReviewUpdateRequest;
import com.mopl.review.service.ReviewService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewController.class)
@AutoConfigureMockMvc(addFilters = false)
class ReviewControllerTest {

    private static final UUID REVIEW_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID CONTENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID AUTHOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean ReviewService reviewService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── POST /api/reviews ────────────────────────────────────────────────────

    @Test
    @DisplayName("리뷰 생성 성공 시 201과 ReviewDto를 반환한다")
    void create_success() throws Exception {
        setAuth(AUTHOR_ID);
        when(reviewService.create(any(), eq(AUTHOR_ID))).thenReturn(sampleDto());

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewCreateRequest(CONTENT_ID, "재밌어요", new BigDecimal("4.5")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(REVIEW_ID.toString()))
                .andExpect(jsonPath("$.text").value("재밌어요"))
                .andExpect(jsonPath("$.author.userId").value(AUTHOR_ID.toString()));
    }

    @Test
    @DisplayName("text가 빈 값이면 400을 반환한다")
    void create_fail_blankText() throws Exception {
        setAuth(AUTHOR_ID);

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewCreateRequest(CONTENT_ID, "", new BigDecimal("4.5")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("rating이 범위를 벗어나면 400을 반환한다")
    void create_fail_ratingOutOfRange() throws Exception {
        setAuth(AUTHOR_ID);

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("5.5")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("미인증 사용자가 생성 시도 시 401을 반환한다")
    void create_fail_unauthorized() throws Exception {
        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("4.5")))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("인증 principal이 UUID 형식이 아니면 401을 반환한다")
    void create_fail_malformedPrincipal() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("not-a-uuid", null, List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("4.5")))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠에 생성 시 404를 반환한다")
    void create_fail_contentNotFound() throws Exception {
        setAuth(AUTHOR_ID);
        when(reviewService.create(any(), eq(AUTHOR_ID)))
                .thenThrow(new BusinessException(ErrorCode.CONTENT_NOT_FOUND));

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("4.5")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CONTENT_404_1"));
    }

    @Test
    @DisplayName("이미 작성한 리뷰가 있으면 409를 반환한다")
    void create_fail_duplicate() throws Exception {
        setAuth(AUTHOR_ID);
        when(reviewService.create(any(), eq(AUTHOR_ID)))
                .thenThrow(new BusinessException(ErrorCode.REVIEW_DUPLICATE));

        mockMvc.perform(post("/api/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewCreateRequest(CONTENT_ID, "text", new BigDecimal("4.5")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_409_1"));
    }

    // ── GET /api/reviews ─────────────────────────────────────────────────────

    @Test
    @DisplayName("리뷰 목록 조회 시 CursorResponse를 반환한다")
    void getList_success() throws Exception {
        CursorResponse<ReviewDto> response = CursorResponse.of(
                List.of(sampleDto()), null, null, false, 1L, "createdAt", "DESCENDING");
        when(reviewService.getList(any(), any(), any(), eq(10), eq("createdAt"), eq("DESCENDING")))
                .thenReturn(response);

        mockMvc.perform(get("/api/reviews")
                        .param("limit", "10")
                        .param("sortBy", "createdAt")
                        .param("sortDirection", "DESCENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(REVIEW_ID.toString()))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("limit이 0 이하면 400을 반환한다")
    void getList_fail_invalidLimit() throws Exception {
        mockMvc.perform(get("/api/reviews")
                        .param("limit", "0")
                        .param("sortBy", "createdAt")
                        .param("sortDirection", "DESCENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("limit이 100 초과면 400을 반환한다")
    void getList_fail_limitExceedsMax() throws Exception {
        mockMvc.perform(get("/api/reviews")
                        .param("limit", "101")
                        .param("sortBy", "createdAt")
                        .param("sortDirection", "DESCENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sortBy가 허용값이 아니면 400을 반환한다")
    void getList_fail_invalidSortBy() throws Exception {
        mockMvc.perform(get("/api/reviews")
                        .param("limit", "10")
                        .param("sortBy", "invalidField")
                        .param("sortDirection", "DESCENDING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sortDirection이 허용값이 아니면 400을 반환한다")
    void getList_fail_invalidSortDirection() throws Exception {
        mockMvc.perform(get("/api/reviews")
                        .param("limit", "10")
                        .param("sortBy", "createdAt")
                        .param("sortDirection", "WRONG"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /api/reviews/me ──────────────────────────────────────────────────

    @Test
    @DisplayName("내 리뷰 조회 성공 시 200과 ReviewDto를 반환한다")
    void getMyReview_success() throws Exception {
        setAuth(AUTHOR_ID);
        when(reviewService.getMyReview(CONTENT_ID, AUTHOR_ID)).thenReturn(sampleDto());

        mockMvc.perform(get("/api/reviews/me").param("contentId", CONTENT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(REVIEW_ID.toString()))
                .andExpect(jsonPath("$.author.userId").value(AUTHOR_ID.toString()));
    }

    @Test
    @DisplayName("미인증 사용자가 내 리뷰 조회 시도 시 401을 반환한다")
    void getMyReview_fail_unauthorized() throws Exception {
        mockMvc.perform(get("/api/reviews/me").param("contentId", CONTENT_ID.toString()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠에 대한 내 리뷰 조회 시 404를 반환한다")
    void getMyReview_fail_contentNotFound() throws Exception {
        setAuth(AUTHOR_ID);
        when(reviewService.getMyReview(CONTENT_ID, AUTHOR_ID))
                .thenThrow(new BusinessException(ErrorCode.CONTENT_NOT_FOUND));

        mockMvc.perform(get("/api/reviews/me").param("contentId", CONTENT_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CONTENT_404_1"));
    }

    @Test
    @DisplayName("본인이 작성한 리뷰가 없으면 404를 반환한다")
    void getMyReview_fail_reviewNotFound() throws Exception {
        setAuth(AUTHOR_ID);
        when(reviewService.getMyReview(CONTENT_ID, AUTHOR_ID))
                .thenThrow(new BusinessException(ErrorCode.REVIEW_NOT_FOUND));

        mockMvc.perform(get("/api/reviews/me").param("contentId", CONTENT_ID.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_404_1"));
    }

    @Test
    @DisplayName("contentId 파라미터가 없으면 400을 반환한다")
    void getMyReview_fail_missingContentId() throws Exception {
        setAuth(AUTHOR_ID);

        mockMvc.perform(get("/api/reviews/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("contentId가 UUID 형식이 아니면 400을 반환한다")
    void getMyReview_fail_invalidContentIdFormat() throws Exception {
        setAuth(AUTHOR_ID);

        mockMvc.perform(get("/api/reviews/me").param("contentId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(reviewService);
    }

    // ── PATCH /api/reviews/{reviewId} ───────────────────────────────────────

    @Test
    @DisplayName("작성자가 리뷰 수정 시 200과 변경된 ReviewDto를 반환한다")
    void update_success() throws Exception {
        setAuth(AUTHOR_ID);
        when(reviewService.update(eq(REVIEW_ID), any(), eq(AUTHOR_ID))).thenReturn(sampleDto());

        mockMvc.perform(patch("/api/reviews/{reviewId}", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewUpdateRequest("새 리뷰", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("재밌어요"));
    }

    @Test
    @DisplayName("text가 공백만으로 이루어지면 400을 반환한다")
    void update_fail_blankText() throws Exception {
        setAuth(AUTHOR_ID);

        mockMvc.perform(patch("/api/reviews/{reviewId}", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewUpdateRequest("   ", null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("미인증 사용자가 수정 시도 시 401을 반환한다")
    void update_fail_unauthorized() throws Exception {
        mockMvc.perform(patch("/api/reviews/{reviewId}", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewUpdateRequest("text", null))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("작성자가 아니면 수정 시 403을 반환한다")
    void update_fail_forbidden() throws Exception {
        setAuth(OTHER_ID);
        when(reviewService.update(eq(REVIEW_ID), any(), eq(OTHER_ID)))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

        mockMvc.perform(patch("/api/reviews/{reviewId}", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewUpdateRequest("text", null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    @Test
    @DisplayName("존재하지 않는 리뷰 수정 시 404를 반환한다")
    void update_fail_notFound() throws Exception {
        setAuth(AUTHOR_ID);
        when(reviewService.update(eq(REVIEW_ID), any(), eq(AUTHOR_ID)))
                .thenThrow(new BusinessException(ErrorCode.REVIEW_NOT_FOUND));

        mockMvc.perform(patch("/api/reviews/{reviewId}", REVIEW_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReviewUpdateRequest("text", null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_404_1"));
    }

    // ── DELETE /api/reviews/{reviewId} ──────────────────────────────────────

    @Test
    @DisplayName("작성자가 리뷰 삭제 시 204를 반환한다")
    void delete_success() throws Exception {
        setAuth(AUTHOR_ID);
        doNothing().when(reviewService).delete(REVIEW_ID, AUTHOR_ID);

        mockMvc.perform(delete("/api/reviews/{reviewId}", REVIEW_ID))
                .andExpect(status().isNoContent());

        verify(reviewService).delete(REVIEW_ID, AUTHOR_ID);
    }

    @Test
    @DisplayName("미인증 사용자가 삭제 시도 시 401을 반환한다")
    void delete_fail_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/reviews/{reviewId}", REVIEW_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("작성자가 아니면 삭제 시 403을 반환한다")
    void delete_fail_forbidden() throws Exception {
        setAuth(OTHER_ID);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(reviewService).delete(REVIEW_ID, OTHER_ID);

        mockMvc.perform(delete("/api/reviews/{reviewId}", REVIEW_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));
    }

    @Test
    @DisplayName("존재하지 않는 리뷰 삭제 시 404를 반환한다")
    void delete_fail_notFound() throws Exception {
        setAuth(AUTHOR_ID);
        doThrow(new BusinessException(ErrorCode.REVIEW_NOT_FOUND))
                .when(reviewService).delete(REVIEW_ID, AUTHOR_ID);

        mockMvc.perform(delete("/api/reviews/{reviewId}", REVIEW_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_404_1"));
    }

    // ───────────────────────────────────────────────────────────────────

    private void setAuth(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    private ReviewDto sampleDto() {
        return new ReviewDto(
                REVIEW_ID, CONTENT_ID, new UserSummary(AUTHOR_ID, "닉네임", null),
                "재밌어요", new BigDecimal("4.5"));
    }
}
