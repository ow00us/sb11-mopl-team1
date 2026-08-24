package com.mopl.content.controller;

import com.mopl.content.dto.ContentCreateRequest;
import com.mopl.content.dto.ContentDto;
import com.mopl.content.dto.ContentUpdateRequest;
import com.mopl.content.service.ContentService;
import com.mopl.global.common.CursorResponse;
import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 콘텐츠 CRUD REST 엔드포인트를 제공하는 컨트롤러입니다. */
@Validated
@RestController
@RequestMapping("/api/contents")
@RequiredArgsConstructor
public class ContentController {

    private final ContentService contentService;
    private final Validator validator;

    /** 콘텐츠를 생성합니다. 어드민만 호출 가능합니다. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponse(responseCode = "201", description = "콘텐츠 생성 성공")
    public ResponseEntity<ContentDto> create(
            @RequestPart("request") ContentCreateRequest request,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail) {
        requireAdmin();
        validate(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(contentService.create(request, thumbnail));
    }

    /** 콘텐츠를 단건 조회합니다. 공개 API로, 인증이 필요 없습니다. */
    @GetMapping("/{contentId}")
    public ContentDto get(@PathVariable UUID contentId) {
        return contentService.get(contentId);
    }

    /** 커서 페이지네이션으로 콘텐츠 목록을 조회합니다. 공개 API로, 인증이 필요 없습니다. */
    @Operation(
            summary = "콘텐츠 목록 조회 (커서 페이지네이션)",
            description = "커서 페이지네이션으로 콘텐츠 목록을 조회합니다. Elasticsearch 기반 검색으로, "
                    + "keywordLike는 단순 부분 문자열이 아니라 형태소 분석(nori) 기반으로 title/description을 매칭합니다. "
                    + "watcherCount 정렬 값은 최대 60초 지연될 수 있습니다(실시간 아님). 공개 API로 인증이 필요 없습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 (limit/sortBy 범위 오류, cursor/idAfter 조합 오류, 잘못된 cursor 형식 등)"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping
    public CursorResponse<ContentDto> getList(
            @Parameter(description = "콘텐츠 타입", schema = @Schema(allowableValues = {"movie", "tvSeries", "sport"}))
            @RequestParam(required = false) @Pattern(regexp = "movie|tvSeries|sport") String typeEqual,

            @Parameter(description = "검색 키워드 (title/description 대상, 형태소 분석 기반 매칭)")
            @RequestParam(required = false) String keywordLike,

            @Parameter(description = "태그 필터 (전부 포함하는 콘텐츠만 매칭, 최대 20개)")
            @RequestParam(required = false) @Size(max = 20) List<String> tagsIn,

            @Parameter(description = "커서")
            @RequestParam(required = false) String cursor,

            @Parameter(description = "보조 커서")
            @RequestParam(required = false) UUID idAfter,

            @Parameter(description = "한 번에 가져올 개수", required = true)
            @RequestParam @Min(1) @Max(100) int limit,

            @Parameter(description = "정렬 방향", required = true,
                    schema = @Schema(allowableValues = {"ASCENDING", "DESCENDING"}))
            @RequestParam @Pattern(regexp = "ASCENDING|DESCENDING") String sortDirection,

            @Parameter(description = "정렬 기준", required = true,
                    schema = @Schema(allowableValues = {"createdAt", "watcherCount", "averageRating"}))
            @RequestParam @Pattern(regexp = "createdAt|watcherCount|averageRating") String sortBy) {
        return contentService.getList(typeEqual, keywordLike, tagsIn, cursor, idAfter, limit, sortBy, sortDirection);
    }

    /** 콘텐츠를 수정합니다. 어드민만 호출 가능합니다. */
    @PatchMapping(value = "/{contentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ContentDto update(
            @PathVariable UUID contentId,
            @RequestPart("request") ContentUpdateRequest request,
            @RequestPart(value = "thumbnail", required = false) MultipartFile thumbnail) {
        requireAdmin();
        validate(request);
        return contentService.update(contentId, request, thumbnail);
    }

    /** 콘텐츠를 삭제합니다. 어드민만 호출 가능합니다. */
    @DeleteMapping("/{contentId}")
    @ApiResponse(responseCode = "204", description = "콘텐츠 삭제 성공")
    public ResponseEntity<Void> delete(@PathVariable UUID contentId) {
        requireAdmin();
        contentService.delete(contentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * SecurityContext에 ROLE_ADMIN 권한이 실려 있다고 가정하고 체크합니다.
     * ROLE_ADMIN GrantedAuthority가 실제로 어떻게 세팅될지는 A영역 JWT 구현이 끝나야 확정되므로,
     * 이 가정은 그때 다시 검증이 필요합니다.
     */
    private void requireAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        if (!isAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    private <T> void validate(T target) {
        Set<ConstraintViolation<T>> violations = validator.validate(target);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
