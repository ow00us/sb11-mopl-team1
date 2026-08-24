package com.mopl.global.outbox.dto;

import java.util.List;

/**
 * 최종 실패 Outbox 이벤트 목록 응답입니다.
 *
 * <p>{@code totalCount} 를 함께 내려줍니다. 목록은 상한이 걸린 조회라 그것만으로는 남은
 * 규모를 알 수 없습니다. 상한이 20인데 실제로 3,000건이 밀려 있는 상황과 20건뿐인 상황은
 * 운영자가 할 일이 다릅니다.
 *
 * @param totalCount 최종 실패 상태인 전체 레코드 수
 * @param items 발생 시각이 이른 순으로 상한만큼 담은 목록
 */
public record OutboxFailureListResponse(
    long totalCount,
    List<OutboxFailureDto> items
) {
}
