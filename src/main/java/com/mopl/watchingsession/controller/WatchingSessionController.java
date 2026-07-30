package com.mopl.watchingsession.controller;

import com.mopl.global.common.CursorResponse;
import com.mopl.watchingsession.dto.WatchingSessionDto;
import com.mopl.watchingsession.service.WatchingSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "시청 세션 관리")
public class WatchingSessionController {

    private final WatchingSessionService watchingSessionService;

    @Operation(
        summary = "특정 사용자의 시청 세션 조회 (nullable)",
        description = "특정 사용자가 어떤 콘텐츠를 보고 있는지 조회"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청"),
        @ApiResponse(responseCode = "401", description = "인증 오류"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/users/{watcherId}/watching-sessions")
    public ResponseEntity<WatchingSessionDto> get(
        @Parameter(description = "사용자 ID", in = ParameterIn.PATH)
        @PathVariable UUID watcherId
    ) {
        return watchingSessionService.get(watcherId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
        summary = "특정 콘텐츠의 시청 세션 목록 조회 (커서 페이지네이션)",
        description = "특정 콘텐츠를 현재 시청 중인 사용자 목록을 커서 페이지네이션으로 조회합니다. 만료된 세션은 목록에서 제외됩니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 요청 (cursor/idAfter 조합 오류, sortBy 값 오류 등)"),
        @ApiResponse(responseCode = "401", description = "인증 오류"),
        @ApiResponse(responseCode = "404", description = "존재하지 않는 콘텐츠"),
        @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @GetMapping("/contents/{contentId}/watching-sessions")
    public ResponseEntity<CursorResponse<WatchingSessionDto>> getListByContent(
        @Parameter(description = "콘텐츠 ID", in = ParameterIn.PATH)
        @PathVariable UUID contentId,

        @Parameter(description = "시청자 이름 (부분 일치)")
        @RequestParam(required = false) String watcherNameLike,

        @Parameter(description = "커서")
        @RequestParam(required = false) String cursor,

        @Parameter(description = "보조 커서")
        @RequestParam(required = false) UUID idAfter,

        @Parameter(description = "한 번에 가져올 개수", required = true)
        @RequestParam
        @Min(value = 1, message = "limit는 1 이상이어야 합니다.")
        @Max(value = 100, message = "limit는 100 이하여야 합니다.")
        int limit,

        @Parameter(description = "정렬 방향", required = true,
            schema = @Schema(allowableValues = {"ASCENDING", "DESCENDING"}))
        @RequestParam String sortDirection,

        @Parameter(description = "정렬 기준", required = true,
            schema = @Schema(allowableValues = {"createdAt"}))
        @RequestParam String sortBy
    ) {
        return ResponseEntity.ok(
            watchingSessionService.getListByContent(
                contentId, watcherNameLike, cursor, idAfter, limit, sortBy, sortDirection
            )
        );
    }
}
