package com.mopl.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.mopl.user.entity.UserRole;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 관리자 사용자 목록 조회 조건의 Bean Validation 규칙을 검증
 *
 * Spring MVC와 데이터베이스를 실행하지 않고
 * UserListRequest에 선언한 입력값 검증 규칙만 확인하는 단위 테스트
 */
class UserListRequestTest {

    /**
     * DTO에 선언된 Bean Validation 애노테이션을 직접 실행하는 검증기
     */
    private final Validator validator =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("OpenAPI 계약에 맞는 목록 조회 조건은 검증에 성공한다")
    void validate_success() {
        // given
        UserListRequest request = new UserListRequest(
            "user@example.com",
            UserRole.USER,
            false,
            null,
            null,
            20,
            "ASCENDING",
            "email"
        );

        // when
        Set<ConstraintViolation<UserListRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("조회 개수가 없으면 검증에 실패한다")
    void validate_fail_whenLimitIsNull() {
        // given
        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            null,
            null,
            null,
            "ASCENDING",
            "createdAt"
        );

        // when
        Set<ConstraintViolation<UserListRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations)
            .extracting(violation ->
                violation.getPropertyPath().toString()
            )
            .contains("limit");
    }

    @Test
    @DisplayName("조회 개수가 허용 범위를 벗어나면 검증에 실패한다")
    void validate_fail_whenLimitIsOutOfRange() {
        // given
        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            null,
            null,
            101,
            "ASCENDING",
            "createdAt"
        );

        // when
        Set<ConstraintViolation<UserListRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations)
            .extracting(violation ->
                violation.getPropertyPath().toString()
            )
            .contains("limit");
    }

    @Test
    @DisplayName("지원하지 않는 정렬 방향이면 검증에 실패한다")
    void validate_fail_whenSortDirectionIsInvalid() {
        // given
        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            null,
            null,
            20,
            "INVALID",
            "createdAt"
        );

        // when
        Set<ConstraintViolation<UserListRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations)
            .extracting(violation ->
                violation.getPropertyPath().toString()
            )
            .contains("sortDirection");
    }

    @Test
    @DisplayName("지원하지 않는 정렬 기준이면 검증에 실패한다")
    void validate_fail_whenSortByIsInvalid() {
        // given
        UserListRequest request = new UserListRequest(
            null,
            null,
            null,
            null,
            null,
            20,
            "ASCENDING",
            "updatedAt"
        );

        // when
        Set<ConstraintViolation<UserListRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations)
            .extracting(violation ->
                violation.getPropertyPath().toString()
            )
            .contains("sortBy");
    }
}
