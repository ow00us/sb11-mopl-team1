package com.mopl.user.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;

/**
 * OAuth 인증 성공·실패 Redirect URI 설정의
 * Bean Validation 정책을 검증
 */
class OAuthRedirectPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    /**
     * 테스트 클래스에서 공통으로 사용할 Bean Validator를 생성
     */
    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
            Validation.buildDefaultValidatorFactory();

        validator =
            validatorFactory.getValidator();
    }

    /**
     * 테스트 종료 후 ValidatorFactory의 내부 자원을 정리
     */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("OAuth Redirect ConfigurationProperties에 검증이 활성화되어 있다")
    void validationIsEnabledForConfigurationProperties() {
        // when
        boolean validatedAnnotationPresent =
            OAuthRedirectProperties.class
                .isAnnotationPresent(Validated.class);

        // then
        assertThat(validatedAnnotationPresent)
            .isTrue();
    }

    @Test
    @DisplayName("로컬 HTTP Redirect URI는 검증에 성공한다")
    void validate_success_whenLocalHttpUrisAreValid() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("운영 HTTPS Redirect URI는 검증에 성공한다")
    void validate_success_whenHttpsUrisAreValid() {
        // given
        OAuthRedirectProperties properties =
            new OAuthRedirectProperties();

        properties.setSuccessUri(
            URI.create(
                "https://mopl.example.com/oauth/callback"
            )
        );

        properties.setFailureUri(
            URI.create(
                "https://mopl.example.com/sign-in"
            )
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("성공 Redirect URI가 null이면 검증에 실패한다")
    void validate_fail_whenSuccessUriIsNull() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setSuccessUri(null);

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 성공 Redirect URI는 필수입니다."
            );
    }

    @Test
    @DisplayName("실패 Redirect URI가 null이면 검증에 실패한다")
    void validate_fail_whenFailureUriIsNull() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setFailureUri(null);

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 실패 Redirect URI는 필수입니다."
            );
    }

    @Test
    @DisplayName("상대 경로인 성공 Redirect URI는 검증에 실패한다")
    void validate_fail_whenSuccessUriIsRelative() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setSuccessUri(
            URI.create("/oauth/callback")
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsSuccessUriViolation(violations);
    }

    @Test
    @DisplayName("상대 경로인 실패 Redirect URI는 검증에 실패한다")
    void validate_fail_whenFailureUriIsRelative() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setFailureUri(
            URI.create("/sign-in")
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsFailureUriViolation(violations);
    }

    @Test
    @DisplayName("HTTP와 HTTPS가 아닌 Redirect URI는 검증에 실패한다")
    void validate_fail_whenSchemeIsUnsupported() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        /*
         * Redirect에 javascript 스킴을 허용하면 브라우저에서
         * 스크립트가 실행될 수 있으므로 거부해야 한다.
         */
        properties.setSuccessUri(
            URI.create(
                "javascript:alert('oauth')"
            )
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsSuccessUriViolation(violations);
    }

    @Test
    @DisplayName("Host가 없는 HTTP Redirect URI는 검증에 실패한다")
    void validate_fail_whenHostIsMissing() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setSuccessUri(
            URI.create(
                "http:/oauth/callback"
            )
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsSuccessUriViolation(violations);
    }

    @Test
    @DisplayName("User Info가 포함된 Redirect URI는 검증에 실패한다")
    void validate_fail_whenUserInfoIsPresent() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setSuccessUri(
            URI.create(
                "https://user@mopl.example.com/oauth/callback"
            )
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsSuccessUriViolation(violations);
    }

    @Test
    @DisplayName("Query가 포함된 성공 Redirect URI는 검증에 실패한다")
    void validate_fail_whenSuccessUriContainsQuery() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setSuccessUri(
            URI.create(
                "https://mopl.example.com/oauth/callback?token=value"
            )
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsSuccessUriViolation(violations);
    }

    @Test
    @DisplayName("Query가 포함된 실패 Redirect URI는 검증에 실패한다")
    void validate_fail_whenFailureUriContainsQuery() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setFailureUri(
            URI.create(
                "https://mopl.example.com/sign-in?error=detail"
            )
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsFailureUriViolation(violations);
    }

    @Test
    @DisplayName("Fragment가 포함된 성공 Redirect URI는 검증에 실패한다")
    void validate_fail_whenSuccessUriContainsFragment() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setSuccessUri(
            URI.create(
                "https://mopl.example.com/oauth/callback#token"
            )
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsSuccessUriViolation(violations);
    }

    @Test
    @DisplayName("Fragment가 포함된 실패 Redirect URI는 검증에 실패한다")
    void validate_fail_whenFailureUriContainsFragment() {
        // given
        OAuthRedirectProperties properties =
            validProperties();

        properties.setFailureUri(
            URI.create(
                "https://mopl.example.com/sign-in#error"
            )
        );

        // when
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations = validator.validate(properties);

        // then
        assertContainsFailureUriViolation(violations);
    }

    /**
     * 모든 검증 조건을 만족하는 기본 Redirect 설정을 생성
     *
     * @return 정상적인 OAuth Redirect 설정
     */
    private OAuthRedirectProperties validProperties() {
        OAuthRedirectProperties properties =
            new OAuthRedirectProperties();

        properties.setSuccessUri(
            URI.create(
                "http://localhost:5173/oauth/callback"
            )
        );

        properties.setFailureUri(
            URI.create(
                "http://localhost:5173/sign-in"
            )
        );

        return properties;
    }

    /**
     * 성공 Redirect URI 형식 오류가 포함됐는지 확인
     *
     * @param violations Bean Validation 결과
     */
    private void assertContainsSuccessUriViolation(
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations
    ) {
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 성공 Redirect URI는 쿼리와 Fragment가 없는 올바른 HTTP 또는 HTTPS 절대 URI여야 합니다."
            );
    }

    /**
     * 실패 Redirect URI 형식 오류가 포함됐는지 확인
     *
     * @param violations Bean Validation 결과
     */
    private void assertContainsFailureUriViolation(
        Set<ConstraintViolation<OAuthRedirectProperties>>
            violations
    ) {
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "OAuth 실패 Redirect URI는 쿼리와 Fragment가 없는 올바른 HTTP 또는 HTTPS 절대 URI여야 합니다."
            );
    }
}
