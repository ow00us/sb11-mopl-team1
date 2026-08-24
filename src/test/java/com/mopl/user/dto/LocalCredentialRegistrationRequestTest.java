package com.mopl.user.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 로컬 이메일·비밀번호 로그인 수단 추가 요청 DTO 검증
 */
class LocalCredentialRegistrationRequestTest {

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
    @DisplayName("유효한 이메일 인증 코드와 비밀번호는 허용한다")
    void validate_success_whenRequestIsValid() {
        // given
        LocalCredentialRegistrationRequest request =
            validRequest();

        // when
        Set<ConstraintViolation<LocalCredentialRegistrationRequest>>
            violations =
            validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("이메일과 인증 코드 앞뒤 공백을 제거한다")
    void constructor_trimsEmailAndVerificationCode() {
        // when
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "  user@example.com  ",
                "  123456  ",
                "Password1!"
            );

        // then
        assertThat(request.email())
            .isEqualTo(
                "user@example.com"
            );

        assertThat(request.verificationCode())
            .isEqualTo(
                "123456"
            );
    }

    @Test
    @DisplayName("이메일 형식이 올바르지 않으면 검증에 실패한다")
    void validate_fail_whenEmailIsInvalid() {
        // given
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "invalid-email",
                "123456",
                "Password1!"
            );

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "이메일 형식이 올바르지 않습니다."
            );
    }

    @Test
    @DisplayName("인증 코드가 6자리보다 짧으면 검증에 실패한다")
    void validate_fail_whenVerificationCodeIsTooShort() {
        // given
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                "12345",
                "Password1!"
            );

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "인증 코드는 6자리 숫자여야 합니다."
            );
    }

    @Test
    @DisplayName("인증 코드에 숫자가 아닌 문자가 포함되면 검증에 실패한다")
    void validate_fail_whenVerificationCodeContainsLetter() {
        // given
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                "12345a",
                "Password1!"
            );

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "인증 코드는 6자리 숫자여야 합니다."
            );
    }

    @Test
    @DisplayName("비밀번호가 8자보다 짧으면 검증에 실패한다")
    void validate_fail_whenPasswordIsTooShort() {
        // given
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                "123456",
                "Aa1!"
            );

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "비밀번호는 8~72자로 작성 가능합니다."
            );
    }

    @Test
    @DisplayName("비밀번호에 숫자가 없으면 검증에 실패한다")
    void validate_fail_whenPasswordDoesNotContainNumber() {
        // given
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                "123456",
                "Password!"
            );

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
            );
    }

    @Test
    @DisplayName("비밀번호에 특수문자가 없으면 검증에 실패한다")
    void validate_fail_whenPasswordDoesNotContainSpecialCharacter() {
        // given
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                "123456",
                "Password1"
            );

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
            );
    }

    @Test
    @DisplayName("비밀번호에 공백이 포함되면 검증에 실패한다")
    void validate_fail_whenPasswordContainsWhitespace() {
        // given
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                "123456",
                "Password 1!"
            );

        // when
        Set<String> messages =
            validationMessages(request);

        // then
        assertThat(messages)
            .contains(
                "비밀번호는 영문, 숫자, 특수문자를 각각 하나 이상 포함해야 합니다."
            );
    }

    @Test
    @DisplayName("72자의 유효한 비밀번호는 허용한다")
    void validate_success_whenPasswordLengthIs72() {
        // given
        String password =
            "Aa1!" + "a".repeat(68);

        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "user@example.com",
                "123456",
                password
            );

        // when
        Set<ConstraintViolation<LocalCredentialRegistrationRequest>>
            violations =
            validator.validate(request);

        // then
        assertThat(password).hasSize(72);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("문자열 표현에 이메일, 인증 코드와 비밀번호 원문을 노출하지 않는다")
    void toString_masksSensitiveValues() {
        // given
        LocalCredentialRegistrationRequest request =
            new LocalCredentialRegistrationRequest(
                "sensitive@example.com",
                "123456",
                "Password1!"
            );

        // when
        String requestString =
            request.toString();

        // then
        assertThat(requestString)
            .doesNotContain(
                "sensitive@example.com",
                "123456",
                "Password1!"
            )
            .contains(
                "email=***",
                "verificationCode=***",
                "password=***"
            );
    }

    private LocalCredentialRegistrationRequest validRequest() {
        return new LocalCredentialRegistrationRequest(
            "user@example.com",
            "123456",
            "Password1!"
        );
    }

    private Set<String> validationMessages(
        LocalCredentialRegistrationRequest request
    ) {
        return validator.validate(request)
            .stream()
            .map(
                ConstraintViolation::getMessage
            )
            .collect(
                Collectors.toSet()
            );
    }
}
