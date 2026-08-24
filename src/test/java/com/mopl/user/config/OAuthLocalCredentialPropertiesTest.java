package com.mopl.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth 전용 사용자의 로컬 로그인 수단 추가 과정에서 사용하는
 * 이메일 인증 정책 설정을 검증
 */
class OAuthLocalCredentialPropertiesTest {

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
    @DisplayName("ConfigurationProperties 시작 시점 검증이 활성화되어 있다")
    void validationIsEnabledForConfigurationProperties() {
        boolean validatedAnnotationPresent =
            OAuthLocalCredentialProperties.class
                .isAnnotationPresent(Validated.class);

        assertThat(validatedAnnotationPresent)
            .isTrue();
    }

    @Test
    @DisplayName("유효한 이메일 인증 정책 설정은 허용한다")
    void validate_success_whenPolicyIsValid() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("인증 코드 만료 시간이 null이면 검증에 실패한다")
    void validate_fail_whenVerificationExpirationIsNull() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setVerificationExpiration(null);

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 로컬 로그인 인증 코드 만료 시간은 반드시 설정해야 합니다."
            );
    }

    @Test
    @DisplayName("인증 코드 만료 시간이 0이면 검증에 실패한다")
    void validate_fail_whenVerificationExpirationIsZero() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setVerificationExpiration(
            Duration.ZERO
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                durationPolicyErrorMessage()
            );
    }

    @Test
    @DisplayName("인증 코드 만료 시간이 음수이면 검증에 실패한다")
    void validate_fail_whenVerificationExpirationIsNegative() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setVerificationExpiration(
            Duration.ofMinutes(-1)
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                durationPolicyErrorMessage()
            );
    }

    @Test
    @DisplayName("인증 코드 만료 시간에 소수 초가 포함되면 검증에 실패한다")
    void validate_fail_whenVerificationExpirationHasFractionalSecond() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setVerificationExpiration(
            Duration.ofMillis(1500)
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                durationPolicyErrorMessage()
            );
    }

    @Test
    @DisplayName("재전송 대기 시간이 null이면 검증에 실패한다")
    void validate_fail_whenResendCooldownIsNull() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setResendCooldown(null);

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 로컬 로그인 인증 메일 재전송 대기 시간은 반드시 설정해야 합니다."
            );
    }

    @Test
    @DisplayName("재전송 대기 시간이 0이면 검증에 실패한다")
    void validate_fail_whenResendCooldownIsZero() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setResendCooldown(
            Duration.ZERO
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                durationPolicyErrorMessage()
            );
    }

    @Test
    @DisplayName("재전송 대기 시간이 음수이면 검증에 실패한다")
    void validate_fail_whenResendCooldownIsNegative() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setResendCooldown(
            Duration.ofSeconds(-1)
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                durationPolicyErrorMessage()
            );
    }

    @Test
    @DisplayName("재전송 대기 시간에 소수 초가 포함되면 검증에 실패한다")
    void validate_fail_whenResendCooldownHasFractionalSecond() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setResendCooldown(
            Duration.ofMillis(1500)
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                durationPolicyErrorMessage()
            );
    }

    @Test
    @DisplayName("재전송 대기 시간이 인증 코드 만료 시간과 같으면 검증에 실패한다")
    void validate_fail_whenResendCooldownEqualsExpiration() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setVerificationExpiration(
            Duration.ofMinutes(10)
        );

        properties.setResendCooldown(
            Duration.ofMinutes(10)
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                durationPolicyErrorMessage()
            );
    }

    @Test
    @DisplayName("재전송 대기 시간이 인증 코드 만료 시간보다 길면 검증에 실패한다")
    void validate_fail_whenResendCooldownExceedsExpiration() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setVerificationExpiration(
            Duration.ofMinutes(10)
        );

        properties.setResendCooldown(
            Duration.ofMinutes(11)
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                durationPolicyErrorMessage()
            );
    }

    @Test
    @DisplayName("인증 코드 최대 시도 횟수가 null이면 검증에 실패한다")
    void validate_fail_whenMaxAttemptsIsNull() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setMaxAttempts(null);

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 로컬 로그인 인증 코드 최대 시도 횟수는 반드시 설정해야 합니다."
            );
    }

    @Test
    @DisplayName("인증 코드 최대 시도 횟수가 0이면 검증에 실패한다")
    void validate_fail_whenMaxAttemptsIsZero() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setMaxAttempts(0);

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 로컬 로그인 인증 코드 최대 시도 횟수는 1 이상이어야 합니다."
            );
    }

    @Test
    @DisplayName("인증 코드 최대 시도 횟수가 10회를 초과하면 검증에 실패한다")
    void validate_fail_whenMaxAttemptsExceedsTen() {
        // given
        OAuthLocalCredentialProperties properties =
            validProperties();

        properties.setMaxAttempts(11);

        // when
        Set<ConstraintViolation<OAuthLocalCredentialProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 로컬 로그인 인증 코드 최대 시도 횟수는 10 이하여야 합니다."
            );
    }

    /**
     * 각 테스트에서 변경하지 않는 나머지 설정을 유효하게 구성
     */
    private OAuthLocalCredentialProperties validProperties() {
        OAuthLocalCredentialProperties properties =
            new OAuthLocalCredentialProperties();

        properties.setVerificationExpiration(
            Duration.ofMinutes(10)
        );

        properties.setResendCooldown(
            Duration.ofMinutes(1)
        );

        properties.setMaxAttempts(5);

        return properties;
    }

    private String durationPolicyErrorMessage() {
        return "OAuth 로컬 로그인 인증 시간 설정은 "
            + "1초 이상의 정수 초이며 재전송 대기 시간이 "
            + "인증 코드 만료 시간보다 짧아야 합니다.";
    }
}
