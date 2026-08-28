package com.mopl.content.controller;

import com.mopl.content.external.mapping.TmdbContentLocalizationBackfillService;
import com.mopl.content.external.mapping.TmdbContentLocalizationBackfillService.BackfillResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이미 저장된 TMDB 소스 콘텐츠의 제목·설명을 한국어로 재수집하는 관리자 트리거.
 *
 * <p>권한 검사는 {@code SecurityFilterChain}이 {@code /api/admin/**} 경로 단위로 하므로
 * 여기서 별도 체크는 하지 않는다.
 */
@RestController
@RequestMapping("/api/admin/contents/tmdb-localization")
@RequiredArgsConstructor
public class TmdbContentLocalizationAdminController {

    private static final Logger audit = LoggerFactory.getLogger("mopl.audit.content");

    private final TmdbContentLocalizationBackfillService backfillService;

    @PostMapping("/backfill")
    @Operation(summary = "TMDB 콘텐츠 제목·설명 한국어 백필")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "백필 처리/성공/실패 건수")
    })
    public BackfillResult backfill(@AuthenticationPrincipal UUID actorId) {
        audit.info("TMDB 콘텐츠 현지화 백필 요청. actorId={}", actorId);
        BackfillResult result = backfillService.backfill();
        audit.info("TMDB 콘텐츠 현지화 백필 응답. actorId={}, result={}", actorId, result);
        return result;
    }
}
