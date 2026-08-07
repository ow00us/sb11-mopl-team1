package com.mopl.user.dto;

import com.mopl.user.entity.UserRole;
import jakarta.validation.constraints.NotNull;

/**
 * 관리자가 사용자의 권한을 변경할 때 사용하는 요청 DTO
 *
 * PATCH /api/users/{userId}/role 요청의 JSON 본문을 표현
 *
 * 요청 예시:
 * {
 *   "role": "ADMIN"
 * }
 *
 * 현재 프로젝트에서 사용 가능한 권한은 UserRole enum에 정의된 USER와 ADMIN 두 가지
 */
public record UserRoleUpdateRequest(

    /**
     * 사용자에게 새로 적용할 권한
     *
     * UserRole 타입을 사용하므로 USER와 ADMIN만 정상적으로 받을 수 있다.
     *
     * 요청에 "MANAGER"처럼 UserRole에 존재하지 않는 문자열이 전달되면
     * Jackson의 JSON 역직렬화 단계에서 요청을 변환하지 못하므로
     * Controller 메서드가 실행되기 전에 400 Bad Request가 반환된다.
     *
     * role 필드가 누락되거나 null로 전달된 경우에는
     * @NotNull 검증이 실패하여 400 Bad Request로 처리된다.
     */
    @NotNull(message = "사용자 권한을 입력해주세요.")
    UserRole role

) {
}
