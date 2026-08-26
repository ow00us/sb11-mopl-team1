package com.mopl.global.outbox.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 최종 실패 이벤트를 건너뛰기로 종결하는 요청입니다.
 *
 * <p>사유를 선택 값으로 두지 않습니다. 이 전환은 이벤트를 보내지 않아도 된다는 업무 판단이고,
 * 판단에는 근거가 남아야 합니다. 사유가 비어 있으면 나중에 그 행을 보고 무슨 일이 있었는지 알
 * 수 없습니다.
 *
 * @param reason 건너뛴 사유
 */
public record OutboxSkipRequest(
    @NotBlank
    @Size(max = 1000)
    String reason
) {
}
