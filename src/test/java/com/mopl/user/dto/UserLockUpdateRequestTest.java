package com.mopl.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UserLockUpdateRequest의 계정 잠금 상태 입력 정책을 검증
 *
 * Spring MVC와 데이터베이스를 실행하지 않고
 * Bean Validation 규칙만 확인하는 DTO 단위 테스트
 */
class UserLockUpdateRequestTest {

    /**
     * DTO에 선언된 @NotNull을 실행하는 Bean Validation 검증기
     */
    private final Validator validator =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("locked가 true이면 검증에 성공한다")
    void validate_success_whenLockedIsTrue() {
        // given
        UserLockUpdateRequest request =
            new UserLockUpdateRequest(true);

        // when
        Set<ConstraintViolation<UserLockUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("locked가 false이면 검증에 성공한다")
    void validate_success_whenLockedIsFalse() {
        // given
        /*
         * false는 필드 누락이 아니라 계정 잠금 해제를 의미하는 정상적인 요청 값
         */
        UserLockUpdateRequest request =
            new UserLockUpdateRequest(false);

        // when
        Set<ConstraintViolation<UserLockUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("locked가 null이면 검증에 실패한다")
    void validate_fail_whenLockedIsNull() {
        // given
        UserLockUpdateRequest request =
            new UserLockUpdateRequest(null);

        // when
        Set<ConstraintViolation<UserLockUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).hasSize(1);

        ConstraintViolation<UserLockUpdateRequest> violation =
            violations.iterator().next();

        assertThat(violation.getPropertyPath().toString())
            .isEqualTo("locked");

        assertThat(violation.getMessage())
            .isEqualTo("계정 잠금 상태를 입력해주세요.");
    }
}
