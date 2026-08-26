package com.mopl.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 로컬 로그인 이메일 인증 요청 DTO 검증
 */
class LocalCredentialEmailVerificationRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
            Validation.buildDefaultValidatorFactory();

        validator =
            validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("올바른 이메일은 검증에 성공한다")
    void validate_success_whenEmailIsValid() {
        // given
        LocalCredentialEmailVerificationRequest request =
            new LocalCredentialEmailVerificationRequest(
                "user@example.com"
            );

        // when
        Set<ConstraintViolation<LocalCredentialEmailVerificationRequest>>
            violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("이메일 앞뒤 공백을 제거한다")
    void constructor_trimsEmail() {
        // when
        LocalCredentialEmailVerificationRequest request =
            new LocalCredentialEmailVerificationRequest(
                "  user@example.com  "
            );

        // then
        assertThat(request.email())
            .isEqualTo(
                "user@example.com"
            );
    }

    @Test
    @DisplayName("이메일이 null이면 검증에 실패한다")
    void validate_fail_whenEmailIsNull() {
        // given
        LocalCredentialEmailVerificationRequest request =
            new LocalCredentialEmailVerificationRequest(
                null
            );

        // when
        Set<ConstraintViolation<LocalCredentialEmailVerificationRequest>>
            violations =
            validator.validate(request);

        // then
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("이메일이 공백이면 검증에 실패한다")
    void validate_fail_whenEmailIsBlank() {
        // given
        LocalCredentialEmailVerificationRequest request =
            new LocalCredentialEmailVerificationRequest(
                "   "
            );

        // when
        Set<ConstraintViolation<LocalCredentialEmailVerificationRequest>>
            violations =
            validator.validate(request);

        // then
        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("이메일 형식이 올바르지 않으면 검증에 실패한다")
    void validate_fail_whenEmailFormatIsInvalid() {
        // given
        LocalCredentialEmailVerificationRequest request =
            new LocalCredentialEmailVerificationRequest(
                "invalid-email"
            );

        // when
        Set<ConstraintViolation<LocalCredentialEmailVerificationRequest>>
            violations =
            validator.validate(request);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "이메일 형식이 올바르지 않습니다."
            );
    }

    @Test
    @DisplayName("이메일이 100자를 초과하면 검증에 실패한다")
    void validate_fail_whenEmailExceedsMaximumLength() {
        // given
        String longEmail =
            "a".repeat(89)
                + "@example.com";

        LocalCredentialEmailVerificationRequest request =
            new LocalCredentialEmailVerificationRequest(
                longEmail
            );

        // when
        Set<ConstraintViolation<LocalCredentialEmailVerificationRequest>>
            violations =
            validator.validate(request);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "이메일은 100자 이하로 작성 가능합니다."
            );
    }
}
