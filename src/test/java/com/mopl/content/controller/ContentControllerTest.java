package com.mopl.content.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.entity.ContentType;
import com.mopl.content.service.ContentService;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ContentController.class)
@AutoConfigureMockMvc(addFilters = false)
class ContentControllerTest {

    private static final UUID CONTENT_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean ContentService contentService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── POST /api/contents ──────────────────────────────────────────────────

    @Test
    @DisplayName("어드민이 콘텐츠 생성 시 201과 ContentDto를 반환한다")
    void create_success() throws Exception {
        setAuth(ADMIN_ID, true);
        when(contentService.create(any(), any())).thenReturn(sampleDto());

        mockMvc.perform(multipart("/api/contents")
                        .file(requestPart(new ContentCreateRequest(
                                ContentType.MOVIE, "제목", "설명", List.of("action"))))
                        .file(thumbnailPart()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONTENT_ID.toString()))
                .andExpect(jsonPath("$.type").value("movie"))
                .andExpect(jsonPath("$.title").value("제목"));
    }

    @Test
    @DisplayName("title이 빈 값이면 400을 반환한다")
    void create_fail_blankTitle() throws Exception {
        setAuth(ADMIN_ID, true);

        mockMvc.perform(multipart("/api/contents")
                        .file(requestPart(new ContentCreateRequest(
                                ContentType.MOVIE, "", "설명", List.of())))
                        .file(thumbnailPart()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("type이 누락되면 400을 반환한다")
    void create_fail_missingType() throws Exception {
        setAuth(ADMIN_ID, true);
        MockMultipartFile request = new MockMultipartFile("request", "", "application/json",
                "{\"title\":\"제목\",\"description\":\"설명\",\"tags\":[]}".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/contents")
                        .file(request)
                        .file(thumbnailPart()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("미인증 사용자가 생성 시도 시 401을 반환한다")
    void create_fail_unauthorized() throws Exception {
        mockMvc.perform(multipart("/api/contents")
                        .file(requestPart(new ContentCreateRequest(
                                ContentType.MOVIE, "제목", "설명", List.of())))
                        .file(thumbnailPart()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("어드민이 아닌 사용자가 생성 시도 시 403을 반환한다")
    void create_fail_forbidden() throws Exception {
        setAuth(USER_ID, false);

        mockMvc.perform(multipart("/api/contents")
                        .file(requestPart(new ContentCreateRequest(
                                ContentType.MOVIE, "제목", "설명", List.of())))
                        .file(thumbnailPart()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("미인증 사용자가 유효하지 않은 값으로 생성 시도해도 400이 아니라 401을 반환한다")
    void create_fail_unauthorized_beforeValidation() throws Exception {
        mockMvc.perform(multipart("/api/contents")
                        .file(requestPart(new ContentCreateRequest(null, "", "", null)))
                        .file(thumbnailPart()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("어드민이 아닌 사용자가 유효하지 않은 값으로 생성 시도해도 400이 아니라 403을 반환한다")
    void create_fail_forbidden_beforeValidation() throws Exception {
        setAuth(USER_ID, false);

        mockMvc.perform(multipart("/api/contents")
                        .file(requestPart(new ContentCreateRequest(null, "", "", null)))
                        .file(thumbnailPart()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(contentService);
    }

    // ── GET /api/contents/{contentId} ───────────────────────────────────────

    @Test
    @DisplayName("콘텐츠 단건 조회 성공 시 200과 ContentDto를 반환한다(공개 API)")
    void get_success() throws Exception {
        when(contentService.get(CONTENT_ID)).thenReturn(sampleDto());

        mockMvc.perform(get("/api/contents/{contentId}", CONTENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONTENT_ID.toString()))
                .andExpect(jsonPath("$.type").value("movie"));
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠 조회 시 404를 반환한다")
    void get_fail_notFound() throws Exception {
        when(contentService.get(CONTENT_ID))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/contents/{contentId}", CONTENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    // ── PATCH /api/contents/{contentId} ─────────────────────────────────────

    @Test
    @DisplayName("어드민이 콘텐츠 수정 시 200과 변경된 ContentDto를 반환한다")
    void update_success() throws Exception {
        setAuth(ADMIN_ID, true);
        when(contentService.update(eq(CONTENT_ID), any(), any())).thenReturn(sampleDto());

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/contents/{contentId}", CONTENT_ID)
                        .file(requestPart(new ContentUpdateRequest("새 제목", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제목"));
    }

    @Test
    @DisplayName("title이 255자를 초과하면 400을 반환한다")
    void update_fail_titleTooLong() throws Exception {
        setAuth(ADMIN_ID, true);
        String tooLong = "a".repeat(256);

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/contents/{contentId}", CONTENT_ID)
                        .file(requestPart(new ContentUpdateRequest(tooLong, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("COMMON_400_1"));

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("미인증 사용자가 수정 시도 시 401을 반환한다")
    void update_fail_unauthorized() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/contents/{contentId}", CONTENT_ID)
                        .file(requestPart(new ContentUpdateRequest("제목", null, null))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("어드민이 아닌 사용자가 수정 시도 시 403을 반환한다")
    void update_fail_forbidden() throws Exception {
        setAuth(USER_ID, false);

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/contents/{contentId}", CONTENT_ID)
                        .file(requestPart(new ContentUpdateRequest("제목", null, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("미인증 사용자가 유효하지 않은 값으로 수정 시도해도 400이 아니라 401을 반환한다")
    void update_fail_unauthorized_beforeValidation() throws Exception {
        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/contents/{contentId}", CONTENT_ID)
                        .file(requestPart(new ContentUpdateRequest("a".repeat(256), null, null))))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("어드민이 아닌 사용자가 유효하지 않은 값으로 수정 시도해도 400이 아니라 403을 반환한다")
    void update_fail_forbidden_beforeValidation() throws Exception {
        setAuth(USER_ID, false);

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/contents/{contentId}", CONTENT_ID)
                        .file(requestPart(new ContentUpdateRequest("a".repeat(256), null, null))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠 수정 시 404를 반환한다")
    void update_fail_notFound() throws Exception {
        setAuth(ADMIN_ID, true);
        when(contentService.update(eq(CONTENT_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/api/contents/{contentId}", CONTENT_ID)
                        .file(requestPart(new ContentUpdateRequest("제목", null, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    // ── DELETE /api/contents/{contentId} ────────────────────────────────────

    @Test
    @DisplayName("어드민이 콘텐츠 삭제 시 204를 반환한다")
    void delete_success() throws Exception {
        setAuth(ADMIN_ID, true);
        doNothing().when(contentService).delete(CONTENT_ID);

        mockMvc.perform(delete("/api/contents/{contentId}", CONTENT_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("미인증 사용자가 삭제 시도 시 401을 반환한다")
    void delete_fail_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/contents/{contentId}", CONTENT_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("어드민이 아닌 사용자가 삭제 시도 시 403을 반환한다")
    void delete_fail_forbidden() throws Exception {
        setAuth(USER_ID, false);

        mockMvc.perform(delete("/api/contents/{contentId}", CONTENT_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COMMON_403_1"));

        verifyNoInteractions(contentService);
    }

    @Test
    @DisplayName("존재하지 않는 콘텐츠 삭제 시 404를 반환한다")
    void delete_fail_notFound() throws Exception {
        setAuth(ADMIN_ID, true);
        doThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND))
                .when(contentService).delete(CONTENT_ID);

        mockMvc.perform(delete("/api/contents/{contentId}", CONTENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COMMON_404_1"));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private void setAuth(UUID userId, boolean isAdmin) {
        var authorities = isAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.<SimpleGrantedAuthority>of();
        var auth = new UsernamePasswordAuthenticationToken(userId.toString(), null, authorities);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    private MockMultipartFile requestPart(Object body) throws Exception {
        return new MockMultipartFile("request", "", "application/json",
                objectMapper.writeValueAsBytes(body));
    }

    private MockMultipartFile thumbnailPart() {
        return new MockMultipartFile("thumbnail", "thumb.png", "image/png", new byte[]{1, 2, 3});
    }

    private ContentDto sampleDto() {
        return new ContentDto(CONTENT_ID, ContentType.MOVIE, "제목", "설명",
                "https://placeholder.mopl.local/thumbnails/thumb.png",
                Set.of("action"), BigDecimal.ZERO, 0L, 0L);
    }
}