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
 * Refresh Token Cookie 설정값의 Bean Validation 정책을 검증
 *
 * <p>환경 변수로 잘못된 Cookie 설정이 전달되면 브라우저가 Cookie를
 * 저장하지 않을 수 있습니다. 따라서 잘못된 설정을 요청 처리 시점이 아니라
 * 애플리케이션 시작 시점에 발견할 수 있도록 설정 제약 조건을 검증합니다.</p>
 */
class RefreshTokenCookiePropertiesTest {

    /**
     * Bean Validation 실행에 사용되는 ValidatorFactory
     *
     * <p>테스트 클래스 전체에서 한 번만 생성하고 모든 테스트가 끝난 뒤
     * close()를 호출해 내부 리소스를 정리합니다.</p>
     */
    private static ValidatorFactory validatorFactory;

    /**
     * RefreshTokenCookieProperties에 선언된 Bean Validation
     * 어노테이션을 실제로 검사하는 Validator
     */
    private static Validator validator;

    /**
     * 모든 테스트 실행 전에 Validator를 한 번 생성
     */
    @BeforeAll
    static void setUpValidator() {
        validatorFactory =
            Validation.buildDefaultValidatorFactory();

        validator =
            validatorFactory.getValidator();
    }

    /**
     * 모든 테스트가 끝난 뒤 ValidatorFactory 리소스를 정리
     */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("ConfigurationProperties에 시작 시점 검증이 활성화되어 있다")
    void validationIsEnabledForConfigurationProperties() {
        /*
         * @ConfigurationProperties에 제약 조건만 선언하고 @Validated를
         * 누락하면 설정 바인딩 시 검증이 실행되지 않는다.
         */
        boolean validatedAnnotationPresent =
            RefreshTokenCookieProperties.class
                .isAnnotationPresent(Validated.class);

        assertThat(validatedAnnotationPresent)
            .isTrue();
    }

    @Test
    @DisplayName("SameSite=Lax이고 Secure=false인 로컬 설정은 허용한다")
    void validate_success_whenSameSiteIsLaxAndSecureIsFalse() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        properties.setSameSite("Lax");
        properties.setSecure(false);

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("SameSite=None이고 Secure=true인 교차 사이트 설정은 허용한다")
    void validate_success_whenSameSiteIsNoneAndSecureIsTrue() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        properties.setSameSite("None");
        properties.setSecure(true);

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("SameSite=None인데 Secure=false이면 검증에 실패한다")
    void validate_fail_whenSameSiteIsNoneAndSecureIsFalse() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        properties.setSameSite("None");
        properties.setSecure(false);

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains(
                "Refresh Token Cookie의 SameSite가 None이면 Secure는 true여야 합니다."
            );
    }

    @Test
    @DisplayName("Cookie 이름이 공백이면 검증에 실패한다")
    void validate_fail_whenNameIsBlank() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        properties.setName("   ");

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "name"
        );
    }

    @Test
    @DisplayName("Cookie 이름에 허용되지 않은 문자가 있으면 검증에 실패한다")
    void validate_fail_whenNameContainsInvalidCharacter() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        /*
         * Cookie 이름 정책에서는 영문, 숫자, 밑줄과 하이픈만 허용하므로
         * 공백이 포함된 이름은 사용할 수 없다.
         */
        properties.setName("REFRESH TOKEN");

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "name"
        );
    }

    @Test
    @DisplayName("Cookie 경로가 공백이면 검증에 실패한다")
    void validate_fail_whenPathIsBlank() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        properties.setPath("   ");

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "path"
        );
    }

    @Test
    @DisplayName("Cookie 경로가 슬래시로 시작하지 않으면 검증에 실패한다")
    void validate_fail_whenPathDoesNotStartWithSlash() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        properties.setPath("api/auth");

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "path"
        );
    }

    @Test
    @DisplayName("SameSite가 공백이면 검증에 실패한다")
    void validate_fail_whenSameSiteIsBlank() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        properties.setSameSite("   ");

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "sameSite"
        );
    }

    @Test
    @DisplayName("정의되지 않은 SameSite 값이면 검증에 실패한다")
    void validate_fail_whenSameSiteIsUnsupported() {
        // given
        RefreshTokenCookieProperties properties =
            validProperties();

        properties.setSameSite("Invalid");

        // when
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations = validator.validate(properties);

        // then
        assertHasViolationForProperty(
            violations,
            "sameSite"
        );
    }

    /**
     * 각 테스트가 변경할 수 있는 정상 Cookie 설정 객체를 생성
     *
     * <p>테스트마다 새로운 객체를 반환하여 한 테스트에서 변경한 값이
     * 다른 테스트에 영향을 주지 않도록 합니다.</p>
     *
     * @return 모든 검증 조건을 만족하는 Cookie 설정 객체
     */
    private RefreshTokenCookieProperties validProperties() {
        RefreshTokenCookieProperties properties =
            new RefreshTokenCookieProperties();

        properties.setName("REFRESH_TOKEN");
        properties.setPath("/api/auth");
        properties.setSameSite("Lax");
        properties.setSecure(false);

        return properties;
    }

    /**
     * 특정 설정 필드에서 Bean Validation 오류가 발생했는지 확인
     *
     * <p>@NotBlank와 @Pattern이 동시에 실패하면 하나의 필드에서
     * 여러 위반이 발생할 수 있으므로 위반 개수를 고정하지 않고,
     * 대상 필드가 위반 목록에 포함되어 있는지를 검증합니다.</p>
     *
     * @param violations 검증 결과
     * @param propertyName 오류가 발생해야 하는 설정 필드 이름
     */
    private void assertHasViolationForProperty(
        Set<ConstraintViolation<RefreshTokenCookieProperties>>
            violations,
        String propertyName
    ) {
        assertThat(violations)
            .extracting(violation ->
                violation.getPropertyPath().toString()
            )
            .contains(propertyName);
    }
}
