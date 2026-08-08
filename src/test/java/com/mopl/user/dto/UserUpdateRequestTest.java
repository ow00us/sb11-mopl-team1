package com.mopl.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UserUpdateRequest에 선언한 프로필 수정 입력값 검증 규칙을 확인
 *
 * Spring MVC나 데이터베이스를 실행하지 않고 Bean Validation 규칙만 검증하므로
 * 빠르게 실행할 수 있는 단위 테스트
 */
class UserUpdateRequestTest {

    /**
     * DTO에 선언된 @Size, @Pattern 등의 검증 애노테이션을 실행하는 객체
     */
    private final Validator validator =
        Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("정상적인 이름은 프로필 수정 요청으로 사용할 수 있다")
    void validate_success_whenNameIsValid() {
        // given
        UserUpdateRequest request =
            new UserUpdateRequest("새로운 이름");

        // when
        Set<ConstraintViolation<UserUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("이름이 null이면 기존 이름을 유지할 수 있도록 허용한다")
    void validate_success_whenNameIsNull() {
        // given
        // 프로필 이미지만 변경하는 요청에서는 이름이 전달되지 않을 수 있습니다.
        UserUpdateRequest request =
            new UserUpdateRequest(null);

        // when
        Set<ConstraintViolation<UserUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("이름이 공백으로만 구성되어 있으면 검증에 실패한다")
    void validate_fail_whenNameIsBlank() {
        // given
        UserUpdateRequest request =
            new UserUpdateRequest("   ");

        // when
        Set<ConstraintViolation<UserUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
            .isEqualTo("name");
        assertThat(violations.iterator().next().getMessage())
            .isEqualTo("이름은 공백으로만 작성할 수 없습니다.");
    }

    @Test
    @DisplayName("이름이 30자를 초과하면 검증에 실패한다")
    void validate_fail_whenNameExceedsMaximumLength() {
        // given
        UserUpdateRequest request =
            new UserUpdateRequest("가".repeat(31));

        // when
        Set<ConstraintViolation<UserUpdateRequest>> violations =
            validator.validate(request);

        // then
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
            .isEqualTo("name");
        assertThat(violations.iterator().next().getMessage())
            .isEqualTo("이름은 30자 이하로 작성 가능합니다.");
    }
}
