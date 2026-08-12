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
 * Refresh Token 만료 시간 설정의 Bean Validation 정책을 검증
 *
 * <p>잘못된 만료 시간이 Redis TTL과 Cookie Max-Age에 사용되기 전에
 * 애플리케이션 시작 시점에 차단되는지 확인합니다.</p>
 */
class RefreshTokenPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    /**
     * 테스트 클래스에서 사용할 Bean Validator를 생성
     */
    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
            Validation.buildDefaultValidatorFactory();

        validator =
            validatorFactory.getValidator();
    }

    /**
     * 모든 테스트가 끝난 뒤 ValidatorFactory를 정리
     */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("ConfigurationProperties에 시작 시점 검증이 활성화되어 있다")
    void validationIsEnabledForConfigurationProperties() {
        boolean validatedAnnotationPresent =
            RefreshTokenProperties.class
                .isAnnotationPresent(Validated.class);

        assertThat(validatedAnnotationPresent)
            .isTrue();
    }

    @Test
    @DisplayName("7일의 Refresh Token 만료 시간은 허용한다")
    void validate_success_whenExpirationIsPositive() {
        // given
        RefreshTokenProperties properties =
            new RefreshTokenProperties();

        properties.setExpiration(Duration.ofDays(7));

        // when
        Set<ConstraintViolation<RefreshTokenProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Refresh Token 만료 시간이 null이면 검증에 실패한다")
    void validate_fail_whenExpirationIsNull() {
        // given
        RefreshTokenProperties properties =
            new RefreshTokenProperties();

        properties.setExpiration(null);

        // when
        Set<ConstraintViolation<RefreshTokenProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "Refresh Token 만료 시간은 반드시 설정해야 합니다."
            );
    }

    @Test
    @DisplayName("Refresh Token 만료 시간이 0이면 검증에 실패한다")
    void validate_fail_whenExpirationIsZero() {
        // given
        RefreshTokenProperties properties =
            new RefreshTokenProperties();

        properties.setExpiration(Duration.ZERO);

        // when
        Set<ConstraintViolation<RefreshTokenProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "Refresh Token 만료 시간은 1초 이상의 정수 초여야 합니다."
            );
    }

    @Test
    @DisplayName("Refresh Token 만료 시간이 음수이면 검증에 실패한다")
    void validate_fail_whenExpirationIsNegative() {
        // given
        RefreshTokenProperties properties =
            new RefreshTokenProperties();

        properties.setExpiration(Duration.ofMinutes(-1));

        // when
        Set<ConstraintViolation<RefreshTokenProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "Refresh Token 만료 시간은 1초 이상의 정수 초여야 합니다."
            );
    }

    @Test
    @DisplayName("Refresh Token 만료 시간이 1초 미만이면 검증에 실패한다")
    void validate_fail_whenExpirationIsLessThanOneSecond() {
        // given
        RefreshTokenProperties properties =
            new RefreshTokenProperties();

        /*
         * Redis에는 1ms TTL로 저장할 수 있지만 Cookie Max-Age는
         * 0초가 되므로 허용하지 않는다.
         */
        properties.setExpiration(
            Duration.ofMillis(1)
        );

        // when
        Set<ConstraintViolation<RefreshTokenProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "Refresh Token 만료 시간은 1초 이상의 정수 초여야 합니다."
            );
    }

    @Test
    @DisplayName("Refresh Token 만료 시간에 소수 초가 포함되면 검증에 실패한다")
    void validate_fail_whenExpirationContainsFractionalSecond() {
        // given
        RefreshTokenProperties properties =
            new RefreshTokenProperties();

        /*
         * Redis에서는 1500ms이지만 Cookie Max-Age는 1초로
         * 내림 처리되므로 허용하지 않는다.
         */
        properties.setExpiration(
            Duration.ofMillis(1500)
        );

        // when
        Set<ConstraintViolation<RefreshTokenProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "Refresh Token 만료 시간은 1초 이상의 정수 초여야 합니다."
            );
    }

    @Test
    @DisplayName("Refresh Token 만료 시간이 정확히 1초이면 허용한다")
    void validate_success_whenExpirationIsExactlyOneSecond() {
        // given
        RefreshTokenProperties properties =
            new RefreshTokenProperties();

        properties.setExpiration(
            Duration.ofSeconds(1)
        );

        // when
        Set<ConstraintViolation<RefreshTokenProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }
}
