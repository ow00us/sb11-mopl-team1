package com.mopl.user.dto;

import com.mopl.user.entity.UserRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * 관리자가 사용자 목록을 조회할 때 사용하는 검색·필터·페이지네이션 조건
 *
 * OpenAPI의 GET /api/users 쿼리 파라미터 계약을 그대로 표현
 *
 * emailLike, roleEqual, locked는 선택 조건이며 null이면 해당 필터를 적용하지 않는다.
 * cursor와 idAfter는 다음 페이지를 조회할 때 함께 사용하는 커서 값
 * limit, sortDirection, sortBy는 OpenAPI에서 필수로 정의된 값
 */
public record UserListRequest(

    /**
     * 이메일 부분 일치 검색어
     *
     * null이면 이메일 검색 조건을 적용하지 않는다.
     */
    String emailLike,

    /**
     * 조회할 사용자 역할
     *
     * null이면 역할과 관계없이 조회하고,
     * 값이 있으면 USER 또는 ADMIN 역할만 조회
     */
    UserRole roleEqual,

    /**
     * 조회할 계정 잠금 상태입니다.
     *
     * Boolean을 사용하는 이유는 다음 세 상태를 구분하기 위해서임.
     *
     * null  : 잠금 상태 필터를 적용하지 않음
     * true  : 잠긴 사용자만 조회
     * false : 잠기지 않은 사용자만 조회
     */
    Boolean locked,

    /**
     * 주 정렬 필드의 마지막 조회 값을 Base64로 인코딩한 커서
     *
     * 첫 페이지에서는 null이며,
     * 다음 페이지 요청에서는 idAfter와 함께 전달해야 한다.
     */
    String cursor,

    /**
     * 동일한 정렬 값을 가진 사용자를 구분하기 위한 UUID 보조 커서
     *
     * 예를 들어 이름이 같은 사용자가 여러 명이면 cursor만으로는
     * 마지막으로 조회한 사용자를 구분할 수 없으므로 사용자 ID를 함께 사용
     */
    UUID idAfter,

    /**
     * 한 번에 조회할 사용자 수
     *
     * Integer를 사용하는 이유는 필수 파라미터가 누락된 경우 null로 받아
     * @NotNull 검증으로 명확하게 거부하기 위해서임.
     * int를 사용하면 누락된 값이 0으로 처리되어 누락과 잘못된 값을 구분하기 어렵다.
     */
    @NotNull(message = "조회 개수를 입력해주세요.")
    @Min(value = 1, message = "조회 개수는 1 이상이어야 합니다.")
    @Max(value = 100, message = "조회 개수는 100 이하여야 합니다.")
    Integer limit,

    /**
     * 사용자 목록 정렬 방향
     *
     * OpenAPI 계약에 따라 ASCENDING 또는 DESCENDING만 허용
     */
    @NotBlank(message = "정렬 방향을 입력해주세요.")
    @Pattern(
        regexp = "ASCENDING|DESCENDING",
        message = "정렬 방향은 ASCENDING 또는 DESCENDING이어야 합니다."
    )
    String sortDirection,

    /**
     * 사용자 목록 정렬 기준
     *
     * OpenAPI에 선언된 name, email, createdAt, locked, role만 허용
     */
    @NotBlank(message = "정렬 기준을 입력해주세요.")
    @Pattern(
        regexp = "name|email|createdAt|locked|role",
        message = "지원하지 않는 정렬 기준입니다."
    )
    String sortBy

) {
}
