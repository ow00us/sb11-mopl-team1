package com.mopl.user.config;

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
import org.springframework.validation.annotation.Validated;

/**
 * OAuth 로컬 로그인 인증 메일 설정 검증
 */
class OAuthLocalCredentialMailPropertiesTest {

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
        assertThat(
            OAuthLocalCredentialMailProperties.class
                .isAnnotationPresent(
                    Validated.class
                )
        ).isTrue();
    }

    @Test
    @DisplayName("올바른 발신 주소와 제목은 허용한다")
    void validate_success_whenPropertiesAreValid() {
        // given
        OAuthLocalCredentialMailProperties properties =
            validProperties();

        // when
        Set<ConstraintViolation<OAuthLocalCredentialMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("발신 주소가 공백이면 검증에 실패한다")
    void validate_fail_whenFromAddressIsBlank() {
        // given
        OAuthLocalCredentialMailProperties properties =
            validProperties();

        properties.setFromAddress("   ");

        // when
        Set<ConstraintViolation<OAuthLocalCredentialMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "fromAddress"
        );
    }

    @Test
    @DisplayName("발신 주소가 이메일 형식이 아니면 검증에 실패한다")
    void validate_fail_whenFromAddressIsInvalid() {
        // given
        OAuthLocalCredentialMailProperties properties =
            validProperties();

        properties.setFromAddress(
            "invalid-email"
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "fromAddress"
        );
    }

    @Test
    @DisplayName("메일 제목이 공백이면 검증에 실패한다")
    void validate_fail_whenSubjectIsBlank() {
        // given
        OAuthLocalCredentialMailProperties properties =
            validProperties();

        properties.setSubject("   ");

        // when
        Set<ConstraintViolation<OAuthLocalCredentialMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "subject"
        );
    }

    @Test
    @DisplayName("메일 제목에 줄바꿈 문자가 있으면 검증에 실패한다")
    void validate_fail_whenSubjectContainsNewLine() {
        // given
        OAuthLocalCredentialMailProperties properties =
            validProperties();

        properties.setSubject(
            "[모두의 플리] 인증 코드\r\nBcc: attacker@example.com"
        );

        // when
        Set<ConstraintViolation<OAuthLocalCredentialMailProperties>>
            violations =
            validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "subject"
        );
    }

    private OAuthLocalCredentialMailProperties validProperties() {
        OAuthLocalCredentialMailProperties properties =
            new OAuthLocalCredentialMailProperties();

        properties.setFromAddress(
            "no-reply@mopl.local"
        );

        properties.setSubject(
            "[모두의 플리] 이메일 인증 코드 안내"
        );

        return properties;
    }

    private void assertHasViolationForProperty(
        Set<ConstraintViolation<OAuthLocalCredentialMailProperties>>
            violations,
        String propertyName
    ) {
        assertThat(violations)
            .extracting(
                violation ->
                    violation
                        .getPropertyPath()
                        .toString()
            )
            .contains(propertyName);
    }
}
