package com.mopl.global.outbox.controller;

import com.mopl.global.exception.BusinessException;
import com.mopl.global.exception.ErrorCode;
import com.mopl.global.outbox.OutboxFailureService;
import com.mopl.global.outbox.OutboxRequeueOutcome;
import com.mopl.global.outbox.OutboxSkipOutcome;
import com.mopl.global.outbox.dto.OutboxFailureDto;
import com.mopl.global.outbox.dto.OutboxFailureListResponse;
import com.mopl.global.outbox.dto.OutboxSkipRequest;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 최종 실패한 Outbox 이벤트를 운영자가 확인하고 종결하는 경계입니다.
 *
 * <p>최종 실패는 자동 relay 대상에서 빠집니다. 이 경로가 없으면 이미 커밋된 도메인 변경에
 * 대한 이벤트가 영영 발행되지 않고, 소비자 쪽 상태가 조용히 어긋난 채로 남습니다.
 *
 * <p>끝내는 방법은 둘입니다. 원인을 고쳤으면 다시 발행 대기로 돌리고, 보내지 않아도 된다고
 * 판단했으면 사유를 남기고 건너뜁니다. 어느 쪽이든 행을 지우지 않습니다.
 *
 * <p>단건 경로만 둡니다. 원인을 확인하지 않은 일괄 처리는 같은 실패와 부하를 그대로
 * 반복합니다. 일괄 경로가 필요해지면 별도 상한과 확인 절차를 두고 따로 붙입니다.
 *
 * <p>권한 검사는 {@code SecurityFilterChain} 이 경로 단위로 합니다. 요청 본문 역직렬화나
 * 검증보다 먼저 걸러야 권한 없는 요청이 애플리케이션 코드에 닿지 않습니다.
 */
@Validated
@RestController
@RequestMapping("/api/admin/outbox/failures")
public class OutboxAdminController {

    /**
     * 감사 로그 전용 logger 입니다.
     *
     * <p>이름을 따로 두면 배포 환경에서 이 로그만 다른 곳으로 보내거나 더 오래 보관할 수
     * 있습니다. 누가 무엇을 어떻게 했는지는 애플리케이션 진단 로그와 보존 기준이 다릅니다.
     */
    private static final Logger audit = LoggerFactory.getLogger("mopl.audit.outbox");

    private final OutboxFailureService outboxFailureService;

    public OutboxAdminController(OutboxFailureService outboxFailureService) {
        this.outboxFailureService = outboxFailureService;
    }

    /**
     * 최종 실패한 이벤트를 발생 순으로 조회합니다.
     *
     * <p>상한을 반드시 받습니다. 최종 실패는 사람이 개입할 때까지 지워지지 않으므로, 상한
     * 없는 조회를 두면 한 번의 요청이 밀린 전체를 실어 나릅니다.
     */
    @GetMapping
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "최종 실패 이벤트 목록")
    })
    public OutboxFailureListResponse findFailures(
        @AuthenticationPrincipal UUID actorId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        List<OutboxFailureDto> items = outboxFailureService.findFailed(limit).stream()
            .map(OutboxFailureDto::from)
            .toList();
        long totalCount = outboxFailureService.countFailed();

        audit.info("Outbox 최종 실패 목록 조회. actorId={}, limit={}, returned={}, totalCount={}",
            actorId, limit, items.size(), totalCount);
        return new OutboxFailureListResponse(totalCount, items);
    }

    /**
     * 최종 실패한 이벤트 한 건을 다시 발행 대기로 돌립니다.
     *
     * <p>새 레코드나 새 envelope 을 만들지 않습니다. eventId, 파티션 키, 중복 제거 키가
     * 바뀌면 소비자의 멱등 판정과 파티션 내 순서가 함께 깨집니다. 기존 행의 상태만 되돌리므로
     * 이미 처리에 성공한 이벤트가 다시 나가도 소비자가 걸러냅니다.
     */
    @PostMapping("/{eventId}/requeue")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "발행 대기로 되돌림"),
        @ApiResponse(responseCode = "404", description = "해당 eventId의 이벤트가 없음"),
        @ApiResponse(responseCode = "409", description = "최종 실패 상태가 아니어서 되돌릴 수 없음")
    })
    public ResponseEntity<Void> requeue(
        @AuthenticationPrincipal UUID actorId,
        @PathVariable UUID eventId
    ) {
        OutboxRequeueOutcome outcome = outboxFailureService.requeue(eventId, Instant.now());

        audit.info("Outbox 최종 실패 재처리 요청. actorId={}, eventId={}, outcome={}",
            actorId, eventId, outcome);

        return switch (outcome) {
            case REQUEUED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> throw new BusinessException(ErrorCode.OUTBOX_EVENT_NOT_FOUND)
                .addDetail("eventId", eventId.toString());
            case NOT_FAILED -> throw new BusinessException(ErrorCode.OUTBOX_EVENT_NOT_FAILED)
                .addDetail("eventId", eventId.toString());
        };
    }

    /**
     * 최종 실패한 이벤트 한 건을 보내지 않기로 하고 종결합니다.
     *
     * <p>발행에 성공했다고 표시하거나 행을 지우지 않습니다. 둘 다 판단이 있었다는 사실을
     * 지웁니다. 누가 언제 왜 보내지 않기로 했는지가 이 전환의 내용입니다.
     *
     * <p>같은 요청이 두 번 들어와도 204 입니다. 결과는 "그 이벤트는 건너뛴 상태다"로 같습니다.
     * 두 번째를 거절하면 응답을 못 본 운영자가 자기 판단이 반영됐는지 다시 확인해야 합니다.
     * 감사 정보는 처음 전환 때의 값을 그대로 둡니다.
     */
    @PostMapping("/{eventId}/skip")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "건너뛰기로 종결"),
        @ApiResponse(responseCode = "400", description = "사유가 비어 있음"),
        @ApiResponse(responseCode = "404", description = "해당 eventId의 이벤트가 없음"),
        @ApiResponse(responseCode = "409", description = "최종 실패 상태가 아니어서 건너뛸 수 없음")
    })
    public ResponseEntity<Void> skip(
        @AuthenticationPrincipal UUID actorId,
        @PathVariable UUID eventId,
        @Valid @RequestBody OutboxSkipRequest request
    ) {
        OutboxSkipOutcome outcome =
            outboxFailureService.skip(eventId, actorId, request.reason(), Instant.now());

        audit.info("Outbox 최종 실패 건너뛰기 요청. actorId={}, eventId={}, outcome={}, reason={}",
            actorId, eventId, outcome, request.reason());

        return switch (outcome) {
            case SKIPPED, ALREADY_SKIPPED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> throw new BusinessException(ErrorCode.OUTBOX_EVENT_NOT_FOUND)
                .addDetail("eventId", eventId.toString());
            case NOT_FAILED -> throw new BusinessException(ErrorCode.OUTBOX_EVENT_NOT_FAILED)
                .addDetail("eventId", eventId.toString());
        };
    }
}
