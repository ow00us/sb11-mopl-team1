package com.mopl.user.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 관리자 계정 잠금 상태 변경 요청의 JSON 데이터를 전달하는 DTO
 *
 * PATCH /api/users/{userId}/locked 요청 본문을 표현
 *
 * locked가 true이면 계정을 잠그고,
 * false이면 기존 계정 잠금을 해제
 */
public record UserLockUpdateRequest(

    /**
     * 새로 적용할 계정 잠금 상태
     *
     * Boolean 객체 타입을 사용하여 다음 세 상태를 구분
     *
     * true  : 계정을 잠금
     * false : 계정 잠금을 해제
     * null  : 요청에서 locked 값이 누락되었거나 명시적으로 null이 전달됨
     *
     * 원시 타입 boolean을 사용하면 요청 필드가 누락된 경우에도
     * 기본값 false가 들어가므로, 사용자가 잠금 해제를 요청한 것인지
     * 필드를 누락한 것인지 구분할 수 없다.
     *
     * @NotNull을 적용하여 필드 누락과 null 요청은
     * 400 Bad Request로 처리
     */
    @NotNull(message = "계정 잠금 상태를 입력해주세요.")
    Boolean locked

) {
}
