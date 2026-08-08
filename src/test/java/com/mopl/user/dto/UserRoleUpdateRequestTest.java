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
 * UserRoleUpdateRequest의 사용자 권한 입력 정책을 검증
 *
 * Spring MVC와 데이터베이스를 실행하지 않고
 * DTO에 선언된 Bean Validation 규칙만 확인하는 단위 테스트
 */
class UserRoleUpdateRequestTest {

    /**
     * DTO에 선언된 @NotNull 등의 Bean Validation 애노테이션을
     * 실제로 실행하기 위한 검증기
     */
    private final Validator validator =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("role이 USER이면 검증에 성공한다")
    void validate_success_whenRoleIsUser() {
        // given
        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(UserRole.USER);

        // when
        Set<ConstraintViolation<UserRoleUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("role이 ADMIN이면 검증에 성공한다")
    void validate_success_whenRoleIsAdmin() {
        // given
        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(UserRole.ADMIN);

        // when
        Set<ConstraintViolation<UserRoleUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("role이 null이면 검증에 실패한다")
    void validate_fail_whenRoleIsNull() {
        // given
        UserRoleUpdateRequest request =
            new UserRoleUpdateRequest(null);

        // when
        Set<ConstraintViolation<UserRoleUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).hasSize(1);

        ConstraintViolation<UserRoleUpdateRequest> violation =
            violations.iterator().next();

        assertThat(violation.getPropertyPath().toString())
            .isEqualTo("role");

        assertThat(violation.getMessage())
            .isEqualTo("사용자 권한을 입력해주세요.");
    }
}
